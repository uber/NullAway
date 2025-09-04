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
import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import org.jspecify.annotations.Nullable;

/**
 * Visitor that collects every TypeVar whose symbol is the given Element.
 *
 * <p>Usage: TypeVarWithSymbolCollector v = new TypeVarWithSymbolCollector(elem); v.scan(rootType);
 * Set<TypeVar> matches = v.getMatches();
 */
public final class TypeVarWithSymbolCollector extends Types.DefaultTypeVisitor<Void, Void> {

  private final Element symbol;
  private final Set<TypeVar> matches = new LinkedHashSet<>();

  public TypeVarWithSymbolCollector(Element symbol) {
    this.symbol = symbol;
  }

  /** Walk a (possibly null) type. Safe to call multiple times on different roots. */
  private void scan(@Nullable Type t) {
    if (t != null) {
      t.accept(this, null);
    }
  }

  /** Results (unmodifiable). */
  public Set<TypeVar> getMatches() {
    return Collections.unmodifiableSet(matches);
  }

  // ---- Core matching logic ----
  @Override
  public Void visitTypeVar(TypeVar t, Void p) {
    if (t.tsym == symbol) {
      matches.add(t);
    }
    scan(t.getUpperBound());
    scan(t.getLowerBound());
    return null;
  }

  // ---- Common container types ----
  @Override
  public Void visitClassType(ClassType t, Void p) {
    for (Type arg : t.getTypeArguments()) {
      scan(arg);
    }
    // For inner classes, the enclosing type may also carry args.
    scan(t.getEnclosingType());
    return null;
  }

  @Override
  public Void visitArrayType(ArrayType t, Void p) {
    scan(t.getComponentType());
    return null;
  }

  @Override
  public Void visitWildcardType(WildcardType t, Void p) {
    scan(t.getExtendsBound());
    scan(t.getSuperBound());
    return null;
  }

  @Override
  public Void visitCapturedType(CapturedType t, Void p) {
    scan(t.getUpperBound());
    scan(t.getLowerBound());
    scan(t.wildcard);
    return null;
  }

  // ---- Functional / executable types ----
  @Override
  public Void visitMethodType(MethodType t, Void p) {
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
  public Void visitForAll(ForAll t, Void p) {
    scan(t.qtype);
    for (Type type : t.getTypeArguments()) {
      scan(type);
    }
    return null;
  }

  // ---- Trivial / leaf types we don't need to descend into ----
  @Override
  public Void visitType(Type t, Void p) {
    // Fallback: best-effort traversal via type arguments, if any.
    for (Type arg : t.getTypeArguments()) {
      scan(arg);
    }
    return null;
  }
}
