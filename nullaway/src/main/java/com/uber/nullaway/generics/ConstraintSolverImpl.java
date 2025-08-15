package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeVariable;

/**
 * An implementation of {@link ConstraintSolver} that uses a work-list algorithm to propagate
 * nullability constraints over a graph of type variables and their sub-/supertype relationships.
 */
public final class ConstraintSolverImpl implements ConstraintSolver {
  private final Config config;
  private final CodeAnnotationInfo codeAnnotationInfo;
  private final Handler handler;
  private final VisitorState state;

  public ConstraintSolverImpl(Config config, VisitorState state, NullAway analysis) {
    this.config = config;
    this.codeAnnotationInfo = CodeAnnotationInfo.instance(state.context);
    this.handler = analysis.getHandler();
    this.state = state;
  }

  /* ───────────────────── internal enums & data ───────────────────── */

  private enum NullnessState {
    UNKNOWN,
    NONNULL,
    NULLABLE
  }

  /** Per-variable state (nullability, sub-/supertype edges). */
  private static final class VarState {
    /**
     * Indicates whether the type variable has a @Nullable upper bound, and thus can be @Nullable
     * itself. Not strictly necessary for constraint solving, but allows us to give a more useful
     * diagnostic if we get a contradiction due to the @NonNull upper bound, which could be helpful
     * in the future.
     */
    final boolean nullableAllowed;

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
  public void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType)
      throws UnsatisfiableConstraintsException {
    subtype.accept(new AddSubtypeConstraintsVisitor(localVariableType), supertype);
  }

  class AddSubtypeConstraintsVisitor extends Types.DefaultTypeVisitor<Void, Type> {
    private boolean localVariableType;

    AddSubtypeConstraintsVisitor(boolean localVariableType) {
      this.localVariableType = localVariableType;
    }

    @Override
    public Void visitType(Type subtype, Type supertype) {
      // handle flow into a type variable.  the check for !(subtype instanceof TypeVar) is a
      // small optimization, as that case should be handled in visitTypeVar.
      if (!localVariableType && (supertype instanceof TypeVar) && !(subtype instanceof TypeVar)) {
        directlyConstrainTypePair(subtype, supertype);
      }
      return null;
    }

    @Override
    public Void visitClassType(ClassType subtype, Type supertype) {
      if (supertype instanceof ClassType) {
        Type subtypeAsSuper = state.getTypes().asSuper(subtype, supertype.tsym);
        if (subtypeAsSuper == null || subtypeAsSuper.isRaw() || supertype.isRaw()) {
          return visitType(subtype, supertype);
        }
        // recursing, so set localVariableType to false
        localVariableType = false;
        // constrain type arguments to have identical nullability
        com.sun.tools.javac.util.List<Type> subtypeTypeArguments =
            subtypeAsSuper.getTypeArguments();
        com.sun.tools.javac.util.List<Type> supertypeTypeArguments = supertype.getTypeArguments();
        int numTypeArgs = supertypeTypeArguments.size();
        Verify.verify(numTypeArgs == subtypeTypeArguments.size());
        for (int i = 0; i < numTypeArgs; i++) {
          Type rhsTypeArg = supertypeTypeArguments.get(i);
          Type lhsTypeArg = subtypeTypeArguments.get(i);
          // constrain in both directions
          // TODO should we have a more optimized way to equate two types?  this just makes each
          //  type a subtype of the other
          lhsTypeArg.accept(this, rhsTypeArg);
          rhsTypeArg.accept(this, lhsTypeArg);
        }
      }
      // if supertype is not a ClassType, we still call visitType to handle the case where
      // supertype is a TypeVar
      return visitType(subtype, supertype);
    }

    @Override
    public Void visitArrayType(Type.ArrayType subtype, Type supertype) {
      if (supertype instanceof Type.ArrayType) {
        Type.ArrayType superArrayType = (Type.ArrayType) supertype;
        // recursing, so set localVariableType to false
        localVariableType = false;
        Type subtypeComponentType = subtype.elemtype;
        Type superComponentType = superArrayType.elemtype;
        // constrain in both directions
        subtypeComponentType.accept(this, superComponentType);
        superComponentType.accept(this, subtypeComponentType);
      }
      // if supertype is not an ArrayType, we still call visitType to handle the case where
      // supertype is a TypeVar
      return visitType(subtype, supertype);
    }

    @Override
    public Void visitTypeVar(TypeVar subtype, Type supertype) {
      if (!localVariableType) {
        directlyConstrainTypePair(subtype, supertype);
      }
      return visitType(subtype, supertype);
    }
  }

  @Override
  public Map<TypeVariable, InferredNullability> solve() throws UnsatisfiableConstraintsException {
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
      VarState st = castToNonNull(vars.get(tv));

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
          throw new RuntimeException("Unexpected nullness state: " + st.nullness + " for " + tv);
      }
    }

    /* ---------- build final solution map ---------- */
    Map<TypeVariable, InferredNullability> result = new HashMap<>();
    vars.forEach(
        (tv, st) -> {
          // if the nullness state is UNKNOWN, set it to NONNULL arbitrarily
          // TODO does this matter?  should we use NULLABLE instead?
          NullnessState n =
              (st.nullness == NullnessState.UNKNOWN) ? NullnessState.NONNULL : st.nullness;
          result.put(
              tv,
              n == NullnessState.NULLABLE
                  ? InferredNullability.NULLABLE
                  : InferredNullability.NONNULL);
        });
    return result;
  }

  private void directlyConstrainTypePair(Type s, Type t) throws UnsatisfiableConstraintsException {
    /* variable-to-variable edge */
    if (isTypeVariable(s) && isTypeVariable(t)) {
      TypeVariable sv = (TypeVariable) s;
      TypeVariable tv = (TypeVariable) t;
      getState(sv).supertypes.add(tv);
      getState(tv).subtypes.add(sv);
    }

    /* top-level nullability rules */
    if (isKnownNonNull(t)) {
      requireNonNull(s);
    }
    if (isKnownNullable(s)) {
      requireNullable(t);
    }
  }

  /* ───────────────────── nullability bookkeeping ───────────────────── */

  /** Force {@code tv} to {@code n}. Returns true if state changed. */
  private boolean updateNullness(TypeVariable tv, NullnessState n)
      throws UnsatisfiableConstraintsException {
    VarState st = getState(tv);

    if (st.nullness == n) {
      return false;
    }
    if (st.nullness != NullnessState.UNKNOWN) {
      throw new UnsatisfiableConstraintsException(
          "Contradictory nullability for " + tv + ": " + st.nullness + " vs. " + n);
    }
    if (n == NullnessState.NULLABLE && !st.nullableAllowed) {
      throw new UnsatisfiableConstraintsException(
          tv + " cannot be @Nullable (upper bound is @NonNull)");
    }
    st.nullness = n;
    return true;
  }

  private void requireNullable(Type t) throws UnsatisfiableConstraintsException {
    if (isTypeVariable(t)) {
      updateNullness((TypeVariable) t, NullnessState.NULLABLE);
    } else if (isKnownNonNull(t)) {
      throw new UnsatisfiableConstraintsException("Cannot treat @NonNull type as @Nullable: " + t);
    }
  }

  private void requireNonNull(Type t) throws UnsatisfiableConstraintsException {
    if (isTypeVariable(t)) {
      updateNullness((TypeVariable) t, NullnessState.NONNULL);
    } else if (isKnownNullable(t)) {
      throw new UnsatisfiableConstraintsException("Cannot treat @Nullable type as @NonNull: " + t);
    }
  }

  /* ───────────────────── helpers & stubs ───────────────────── */

  private VarState getState(TypeVariable tv) {
    return vars.computeIfAbsent(tv, v -> new VarState(upperBoundIsNullable(v)));
  }

  private static boolean isTypeVariable(Type t) {
    // for now ignore captures
    return t instanceof TypeVar && !((TypeVar) t).isCaptured();
  }

  private boolean isKnownNullable(Type t) {
    return t instanceof NullType
        || Nullness.hasNullableAnnotation(t.getAnnotationMirrors().stream(), config);
  }

  /** Everything non-nullable *and* non-variable counts as @NonNull. */
  private boolean isKnownNonNull(Type t) {
    return !isKnownNullable(t) && !isTypeVariable(t);
  }

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
    return enclosingElement != null
        && codeAnnotationInfo.isSymbolUnannotated(enclosingElement, config, handler);
  }
}
