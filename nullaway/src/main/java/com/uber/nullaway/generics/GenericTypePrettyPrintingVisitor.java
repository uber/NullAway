package com.uber.nullaway.generics;

import static java.util.stream.Collectors.joining;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;

/**
 * A visitor that pretty prints a generic type including its type-use nullability annotations, for
 * use in error messages.
 *
 * <p>This code is a modified and extended version of code in {@link
 * com.google.errorprone.util.Signatures}
 */
final class GenericTypePrettyPrintingVisitor extends Types.DefaultTypeVisitor<String, Void> {

  private final VisitorState state;

  GenericTypePrettyPrintingVisitor(VisitorState state) {
    this.state = state;
  }

  @Override
  public String visitWildcardType(Type.WildcardType t, Void unused) {
    // NOTE: we have not tested this code yet as we do not yet support wildcard types
    StringBuilder sb = new StringBuilder();
    sb.append(t.kind);
    if (t.kind != BoundKind.UNBOUND) {
      sb.append(t.type.accept(this, null));
    }
    return sb.toString();
  }

  @Override
  public String visitClassType(Type.ClassType t, Void s) {
    StringBuilder sb = new StringBuilder();
    Type enclosingType = t.getEnclosingType();
    if (!ASTHelpers.isSameType(enclosingType, Type.noType, state)) {
      sb.append(enclosingType.accept(this, null)).append('.');
    }
    for (Attribute.TypeCompound compound : t.getAnnotationMirrors()) {
      sb.append('@');
      sb.append(compound.type.accept(this, null));
      sb.append(' ');
    }
    sb.append(t.tsym.getSimpleName());
    if (t.getTypeArguments().nonEmpty()) {
      sb.append('<');
      sb.append(
          t.getTypeArguments().stream().map(a -> a.accept(this, null)).collect(joining(", ")));
      sb.append(">");
    }
    return sb.toString();
  }

  @Override
  public String visitCapturedType(Type.CapturedType t, Void s) {
    return t.wildcard.accept(this, null);
  }

  @Override
  public String visitArrayType(Type.ArrayType t, Void unused) {
    // TODO properly print cases like int @Nullable[]
    return t.elemtype.accept(this, null) + "[]";
  }

  @Override
  public String visitType(Type t, Void s) {
    return t.toString();
  }
}
