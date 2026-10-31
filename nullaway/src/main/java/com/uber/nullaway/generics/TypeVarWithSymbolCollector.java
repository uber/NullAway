package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.CapturedType;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.ForAll;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import org.jspecify.annotations.Nullable;

/**
 * Visitor that collects every TypeVar whose symbol belongs to a given set of Elements.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * TypeVarWithSymbolCollector v = new TypeVarWithSymbolCollector(elem);
 * rootType.accept(v, null);
 * Set<TypeVar> matches = v.getMatches();
 * }</pre>
 *
 * <p>Not safe to run multiple times; create a fresh visitor for each root type to scan.
 */
public final class TypeVarWithSymbolCollector
    extends Types.DefaultTypeVisitor<@Nullable Void, @Nullable Void> {

  private final Set<? extends Element> symbols;

  /** Created on the first match; most traversals find none. */
  private @Nullable Set<TypeVar> matches;

  /** Creates a collector for occurrences of a single type variable symbol. */
  public TypeVarWithSymbolCollector(Element symbol) {
    this(Collections.singleton(symbol));
  }

  /**
   * Creates a collector for occurrences of any of the supplied type variable symbols in one
   * traversal, including occurrences in variable bounds and intersection components. Matches retain
   * traversal order and include distinct annotated occurrences of the same symbol.
   *
   * @param symbols symbols to match; their set must not be modified while this collector is in use
   */
  public TypeVarWithSymbolCollector(Set<? extends Element> symbols) {
    this.symbols = symbols;
  }

  /**
   * Types already visited, compared by identity. Most traversals visit a handful of nested types,
   * so the first {@link #SMALL_SEEN_LIMIT} are kept in a small array and scanned linearly; larger
   * traversals switch to an identity set.
   */
  private static final int SMALL_SEEN_LIMIT = 16;

  private Type @Nullable [] smallSeen;
  private int smallSeenSize;
  private @Nullable Set<Type> seen;

  /** Adds {@code t} to the visited types; returns false if it was already visited. */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
  private boolean markSeen(Type t) {
    if (seen != null) {
      return seen.add(t);
    }
    if (smallSeen == null) {
      smallSeen = new Type[SMALL_SEEN_LIMIT];
    }
    for (int i = 0; i < smallSeenSize; i++) {
      if (smallSeen[i] == t) {
        return false;
      }
    }
    if (smallSeenSize < SMALL_SEEN_LIMIT) {
      smallSeen[smallSeenSize++] = t;
      return true;
    }
    Set<Type> large = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int i = 0; i < smallSeenSize; i++) {
      large.add(smallSeen[i]);
    }
    large.add(t);
    seen = large;
    smallSeen = null;
    return true;
  }

  /** Walk a (possibly null) type. */
  private void scan(@Nullable Type t) {
    if (t != null && markSeen(t)) {
      t.accept(this, null);
    }
  }

  /** Results (unmodifiable). */
  public Set<TypeVar> getMatches() {
    return matches == null ? Collections.emptySet() : Collections.unmodifiableSet(matches);
  }

  // ---- Core matching logic ----
  @Override
  public @Nullable Void visitTypeVar(TypeVar t, @Nullable Void p) {
    if (symbols.contains(t.tsym)) {
      if (matches == null) {
        matches = new LinkedHashSet<>();
      }
      matches.add(t);
    }
    scan(t.getUpperBound());
    scan(t.getLowerBound());
    return null;
  }

  // ---- Common container types ----
  @Override
  public @Nullable Void visitClassType(ClassType t, @Nullable Void p) {
    if (t instanceof Type.IntersectionClassType intersection) {
      for (Type component : intersection.getExplicitComponents()) {
        scan(component);
      }
    }
    for (Type arg : t.getTypeArguments()) {
      scan(arg);
    }
    // For inner classes, the enclosing type may also carry args.
    scan(t.getEnclosingType());
    return null;
  }

  @Override
  public @Nullable Void visitArrayType(ArrayType t, @Nullable Void p) {
    scan(t.getComponentType());
    return null;
  }

  @Override
  public @Nullable Void visitWildcardType(WildcardType t, @Nullable Void p) {
    scan(t.getExtendsBound());
    scan(t.getSuperBound());
    return null;
  }

  @Override
  public @Nullable Void visitCapturedType(CapturedType t, @Nullable Void p) {
    scan(t.getUpperBound());
    scan(t.getLowerBound());
    scan(t.wildcard);
    return null;
  }

  // ---- Functional / executable types ----
  @Override
  public @Nullable Void visitMethodType(MethodType t, @Nullable Void p) {
    scan(t.getReturnType());
    for (Type pt : t.getParameterTypes()) {
      scan(pt);
    }
    for (Type thrown : t.getThrownTypes()) {
      scan(thrown);
    }
    return null;
  }

  @Override
  public @Nullable Void visitForAll(ForAll t, @Nullable Void p) {
    scan(t.qtype);
    for (Type type : t.getTypeArguments()) {
      scan(type);
    }
    return null;
  }

  // ---- Trivial / leaf types we don't need to descend into ----
  @Override
  public @Nullable Void visitType(Type t, @Nullable Void p) {
    // Fallback: best-effort traversal via type arguments, if any.
    for (Type arg : t.getTypeArguments()) {
      scan(arg);
    }
    return null;
  }
}
