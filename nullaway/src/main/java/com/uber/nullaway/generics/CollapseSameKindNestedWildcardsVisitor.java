package com.uber.nullaway.generics;

import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.common.base.Verify;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Normalizes javac type graphs by collapsing directly nested wildcards of the same kind.
 *
 * <p>This specifically handles shapes such as {@code ? extends ? extends T} and the captured-type
 * variants javac can synthesize during generic inference, like {@code ? extends capture-of-?
 * extends T}. The visitor also tracks types currently being visited by identity so it can stop on
 * recursive capture/bound cycles rather than recursing indefinitely.
 */
@SuppressWarnings("ReferenceEquality")
final class CollapseSameKindNestedWildcardsVisitor
    extends Types.DefaultTypeVisitor<Type, @Nullable Void> {

  private final Set<Type> typesInProgress = Collections.newSetFromMap(new IdentityHashMap<>());

  private Type normalize(Type type) {
    if (!typesInProgress.add(type)) {
      return type;
    }
    try {
      return type.accept(this, null);
    } finally {
      typesInProgress.remove(type);
    }
  }

  @Override
  public Type visitMethodType(Type.MethodType t, @Nullable Void unused) {
    List<Type> argtypes = t.argtypes;
    Type restype = t.restype;
    List<Type> thrown = t.thrown;
    List<Type> argtypes1 = visitTypeList(argtypes);
    Type restype1 = normalize(restype);
    List<Type> thrown1 = visitTypeList(thrown);
    if (argtypes1 == argtypes && restype1 == restype && thrown1 == thrown) {
      return t;
    }
    return new Type.MethodType(argtypes1, restype1, thrown1, t.tsym);
  }

  @Override
  public Type visitClassType(Type.ClassType t, @Nullable Void unused) {
    Type outer = t.getEnclosingType();
    Type outer1 = normalize(outer);
    List<Type> typarams = t.getTypeArguments();
    List<Type> typarams1 = visitTypeList(typarams);
    if (outer1 == outer && typarams1 == typarams) {
      return t;
    }
    return TYPE_METADATA_BUILDER.createClassType(t, outer1, typarams1);
  }

  @Override
  public Type visitArrayType(Type.ArrayType t, @Nullable Void unused) {
    Type elemtype = t.elemtype;
    Type elemtype1 = normalize(elemtype);
    if (elemtype1 == elemtype) {
      return t;
    }
    return TYPE_METADATA_BUILDER.createArrayType(t, elemtype1);
  }

  @Override
  public Type visitWildcardType(Type.WildcardType t, @Nullable Void unused) {
    Type bound = t.type;
    if (bound == null) {
      return t;
    }
    Type bound1 = normalize(bound);
    if (bound1 instanceof Type.CapturedType capturedType && capturedType.wildcard.kind == t.kind) {
      return normalize(capturedType.wildcard);
    }
    if (bound1 instanceof Type.WildcardType nestedWildcard && nestedWildcard.kind == t.kind) {
      return nestedWildcard;
    }
    if (bound1 == bound) {
      return t;
    }
    return TYPE_METADATA_BUILDER.createWildcardType(t, bound1);
  }

  @Override
  public Type visitCapturedType(Type.CapturedType t, @Nullable Void unused) {
    Type upper = t.getUpperBound();
    Type upper1 = normalize(upper);
    Type lower = t.getLowerBound();
    Type lower1 = normalize(lower);
    Type wildcardType = normalize(t.wildcard);
    Verify.verify(wildcardType instanceof Type.WildcardType);
    Type.WildcardType wildcard1 = (Type.WildcardType) wildcardType;
    if (upper1 == upper && lower1 == lower && wildcard1 == t.wildcard) {
      return t;
    }
    return new Type.CapturedType(
        (Symbol.TypeSymbol) t.tsym, upper1, upper1, lower1, wildcard1, t.getMetadata());
  }

  @Override
  public Type visitForAll(Type.ForAll t, @Nullable Void unused) {
    Type qtype = t.qtype;
    Type qtype1 = normalize(qtype);
    if (qtype1 == qtype) {
      return t;
    }
    return new Type.ForAll(t.tvars, qtype1);
  }

  @Override
  public Type visitType(Type t, @Nullable Void unused) {
    return t;
  }

  private List<Type> visitTypeList(List<Type> types) {
    ListBuffer<Type> updated = new ListBuffer<>();
    boolean changed = false;
    for (List<Type> current = types; current.nonEmpty(); current = current.tail) {
      Type type = current.head;
      Type updatedType = normalize(type);
      updated.append(updatedType);
      if (updatedType != type) {
        changed = true;
      }
    }
    return changed ? updated.toList() : types;
  }
}
