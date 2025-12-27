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
import javax.lang.model.element.Element;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

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
    final Set<Element> supertypes = new HashSet<>();
    final Set<Element> subtypes = new HashSet<>();

    VarState(boolean nullableAllowed) {
      this.nullableAllowed = nullableAllowed;
    }
  }

  /* All variables seen so far. */
  private final Map<Element, VarState> vars = new HashMap<>();

  /* ───────────────────── public API ───────────────────── */

  @Override
  public void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType)
      throws UnsatisfiableConstraintsException {
    subtype.accept(new AddSubtypeConstraintsVisitor(localVariableType), supertype);
  }

  class AddSubtypeConstraintsVisitor extends Types.DefaultTypeVisitor<@Nullable Void, Type> {
    private boolean localVariableType;

    AddSubtypeConstraintsVisitor(boolean localVariableType) {
      this.localVariableType = localVariableType;
    }

    @Override
    public @Nullable Void visitType(Type subtype, Type supertype) {
      // handle flow into a type variable.  the check for !(subtype instanceof TypeVar) is a
      // small optimization, as that case should be handled in visitTypeVar.
      if (!localVariableType && (supertype instanceof TypeVar) && !(subtype instanceof TypeVar)) {
        directlyConstrainTypePair(subtype, supertype);
      }
      return null;
    }

    @Override
    public @Nullable Void visitClassType(ClassType subtype, Type supertype) {
      if (supertype instanceof ClassType) {
        Type subtypeAsSuper =
            TypeSubstitutionUtils.asSuper(
                state.getTypes(), subtype, (Symbol.ClassSymbol) supertype.tsym, config);
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
    public @Nullable Void visitArrayType(Type.ArrayType subtype, Type supertype) {
      if (supertype instanceof Type.ArrayType superArrayType) {

        // recursing, so set localVariableType to false
        localVariableType = false;
        Type subtypeComponentType = subtype.elemtype;
        Type superComponentType = superArrayType.elemtype;
        // arrays have covariant subtyping; so only constrain in one direction
        subtypeComponentType.accept(this, superComponentType);
      }
      // if supertype is not an ArrayType, we still call visitType to handle the case where
      // supertype is a TypeVar
      return visitType(subtype, supertype);
    }

    @Override
    public @Nullable Void visitTypeVar(TypeVar subtype, Type supertype) {
      if (!localVariableType) {
        directlyConstrainTypePair(subtype, supertype);
      }
      return visitType(subtype, supertype);
    }
  }

  @Override
  public Map<Element, InferredNullability> solve() throws UnsatisfiableConstraintsException {
    /* ---------- work-list propagation of nullability ---------- */
    Deque<Element> work = new ArrayDeque<>();
    vars.forEach(
        (tv, st) -> {
          if (st.nullness != NullnessState.UNKNOWN) {
            work.add(tv);
          }
        });

    while (!work.isEmpty()) {
      Element typeVarElement = work.removeFirst();
      VarState st = castToNonNull(vars.get(typeVarElement));

      switch (st.nullness) {
        case NONNULL:
          /* S <: tv  &  tv NONNULL  ⇒  S NONNULL */
          for (Element sub : st.subtypes) {
            if (updateNullness(sub, NullnessState.NONNULL)) {
              work.add(sub);
            }
          }
          break;

        case NULLABLE:
          /* tv <: T  &  tv NULLABLE  ⇒  T NULLABLE */
          for (Element sup : st.supertypes) {
            if (updateNullness(sup, NullnessState.NULLABLE)) {
              work.add(sup);
            }
          }
          break;

        default: // UNKNOWN
          throw new RuntimeException(
              "Unexpected nullness state: " + st.nullness + " for " + typeVarElement);
      }
    }

    /* ---------- build final solution map ---------- */
    Map<Element, InferredNullability> result = new HashMap<>();
    vars.forEach(
        (tv, st) -> {
          // Note: if the nullness state is UNKNOWN, we infer NONNULL arbitrarily
          // TODO does this matter?  should we use NULLABLE instead?
          result.put(
              tv,
              st.nullness == NullnessState.NULLABLE
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
      getState(sv.asElement()).supertypes.add(tv.asElement());
      getState(tv.asElement()).subtypes.add(sv.asElement());
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
  private boolean updateNullness(Element typeVarElement, NullnessState n)
      throws UnsatisfiableConstraintsException {
    VarState st = getState(typeVarElement);

    if (st.nullness == n) {
      return false;
    }
    if (st.nullness != NullnessState.UNKNOWN) {
      throw new UnsatisfiableConstraintsException(
          "Contradictory nullability for " + typeVarElement + ": " + st.nullness + " vs. " + n);
    }
    if (n == NullnessState.NULLABLE && !st.nullableAllowed) {
      throw new UnsatisfiableConstraintsException(
          typeVarElement + " cannot be @Nullable (upper bound is @NonNull)");
    }
    st.nullness = n;
    return true;
  }

  private void requireNullable(Type t) throws UnsatisfiableConstraintsException {
    if (isTypeVariable(t)) {
      updateNullness(t.asElement(), NullnessState.NULLABLE);
    } else if (isKnownNonNull(t)) {
      throw new UnsatisfiableConstraintsException("Cannot treat @NonNull type as @Nullable: " + t);
    }
  }

  private void requireNonNull(Type t) throws UnsatisfiableConstraintsException {
    if (isTypeVariable(t)) {
      updateNullness(t.asElement(), NullnessState.NONNULL);
    } else if (isKnownNullable(t)) {
      throw new UnsatisfiableConstraintsException("Cannot treat @Nullable type as @NonNull: " + t);
    }
  }

  /* ───────────────────── helpers & stubs ───────────────────── */

  private VarState getState(Element typeVarElement) {
    return vars.computeIfAbsent(typeVarElement, v -> new VarState(upperBoundIsNullable(v)));
  }

  private boolean isTypeVariable(Type t) {
    if (t instanceof TypeVar tv) {

      // For now ignore capture variables, like "capture#1 of ? extends X".  Also, only treat as a
      // type variable if it _doesn't_ have an explicit @Nullable or @NonNull annotation.
      return !tv.isCaptured()
          && !Nullness.hasNullableAnnotation(tv.getAnnotationMirrors().stream(), config)
          && !Nullness.hasNonNullAnnotation(tv.getAnnotationMirrors().stream(), config);
    } else {
      return false;
    }
  }

  private boolean isKnownNullable(Type t) {
    return t instanceof NullType
        || Nullness.hasNullableAnnotation(t.getAnnotationMirrors().stream(), config);
  }

  /** Everything non-nullable *and* non-variable counts as @NonNull. */
  private boolean isKnownNonNull(Type t) {
    return !isKnownNullable(t) && !isTypeVariable(t);
  }

  private boolean upperBoundIsNullable(Element typeVarElement) {
    if (fromUnannotatedMethod(typeVarElement)) {
      return true;
    }
    // first, check if library model overrides the upper bound nullability
    Element enclosingElement = typeVarElement.getEnclosingElement();
    if (enclosingElement instanceof Symbol.MethodSymbol methodSymbol) {

      int typeVarIndex =
          methodSymbol.getTypeParameters().indexOf((Symbol.TypeVariableSymbol) typeVarElement);
      // TODO typeVarIndex is -1 in some cases; see test
      //  com.uber.nullaway.jspecify.GenericMethodTests.instanceGenericMethodWithMethodRefArgument.
      //  Investigate further.
      if (typeVarIndex >= 0
          && handler.onOverrideMethodTypeVariableUpperBound(methodSymbol, typeVarIndex, state)) {
        return true;
      }
    } else if (enclosingElement instanceof Symbol.ClassSymbol classSymbol) {

      int typeVarIndex =
          classSymbol.getTypeParameters().indexOf((Symbol.TypeVariableSymbol) typeVarElement);
      if (typeVarIndex >= 0
          && handler.onOverrideClassTypeVariableUpperBound(classSymbol.toString(), typeVarIndex)) {
        return true;
      }
    }
    // otherwise, check the actual upper bound annotations
    Type upperBound = (Type) ((TypeVariable) typeVarElement.asType()).getUpperBound();
    com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
        upperBound.getAnnotationMirrors();
    return com.uber.nullaway.Nullness.hasNullableAnnotation(annotationMirrors.stream(), config);
  }

  private boolean fromUnannotatedMethod(Element typeVarElement) {
    Symbol enclosingElement = (Symbol) typeVarElement.getEnclosingElement();
    return enclosingElement != null
        && codeAnnotationInfo.isSymbolUnannotated(enclosingElement, config, handler);
  }
}
