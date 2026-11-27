package com.uber.nullaway.generics;

import static java.util.stream.Collectors.joining;

import com.google.errorprone.VisitorState;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A visitor that pretty prints a generic type including its type-use nullability annotations, for
 * use in error messages.
 *
 * <p>Nodes named by {@link TypePath} may be marked, in which case each printed form is bracketed by
 * {@link #MARK_START}, the index of every path that reaches it, {@link #MARK_TEXT}, and {@link
 * #MARK_END}, so that a caller can find its columns and draw a caret under it. A path that the
 * printed type does not reach leaves no bracket, which is how a caller learns that the node it
 * asked about is not printed: an unbounded wildcard prints as {@code ?} and its upper bound appears
 * nowhere.
 *
 * <p>This code is a modified and extended version of code in {@link
 * com.google.errorprone.util.Signatures}
 */
final class GenericTypePrettyPrintingVisitor
    extends Types.DefaultTypeVisitor<String, @Nullable Void> {

  /**
   * Opens the printed form of a marked node, followed by one digit for each requested path that
   * reaches the node. The marker characters cannot occur in a printed type, so the caller can
   * locate the marked node's columns and then strip them.
   */
  static final char MARK_START = '\u0001';

  /** Ends the digits of a marked node and starts its printed form. */
  static final char MARK_TEXT = '\u0003';

  /** Ends the printed form of a marked node. */
  static final char MARK_END = '\u0002';

  private final VisitorState state;

  /**
   * The positions whose printed form is bracketed, in the order the caller numbers them. At most
   * ten, since an index is printed as one digit. Two entries may reach one node, which is then
   * bracketed once with both indices. An entry is {@code null} where the caller has a position for
   * the other side of the comparison and none here, which keeps the indices of the two sides
   * aligned.
   */
  private final List<@Nullable TypePath> markedPaths;

  /** The position of the node being printed, maintained as the visitor descends. */
  private TypePath currentPath = TypePath.root();

  /**
   * Where the names of classes and annotations are imported for a fix that pastes the printed type,
   * or {@code null} where the type is only shown, under simple names.
   */
  private final SuggestedFix.@Nullable Builder fix;

  /**
   * Whether the {@code @Nullable} on the base type, the type itself or, for an array, its innermost
   * element type, is left out because the declaration the fix rewrites states it among its
   * modifiers, outside the type tree the fix replaces.
   */
  private final boolean omitBaseAnnotation;

  GenericTypePrettyPrintingVisitor(VisitorState state) {
    this(state, List.of());
  }

  GenericTypePrettyPrintingVisitor(VisitorState state, List<@Nullable TypePath> markedPaths) {
    this(state, markedPaths, null, false);
  }

  private GenericTypePrettyPrintingVisitor(
      VisitorState state,
      List<@Nullable TypePath> markedPaths,
      SuggestedFix.@Nullable Builder fix,
      boolean omitBaseAnnotation) {
    this.state = state;
    this.markedPaths = markedPaths;
    this.fix = fix;
    this.omitBaseAnnotation = omitBaseAnnotation;
  }

  /**
   * Returns {@code type} printed as source code for {@code fix} to paste into the compilation unit
   * {@code state} is in: a class or annotation that a simple name does not reach there is imported
   * by {@code fix}, or qualified where an import would clash. NullAway's synthetic
   * {@code @Nullable} prints as JSpecify's.
   *
   * @param omitBaseAnnotation whether to leave out the {@code @Nullable} of the base type, the type
   *     itself or, for an array, its innermost element type, because the declaration states it
   *     among its modifiers
   */
  static String printForFix(
      Type type, VisitorState state, SuggestedFix.Builder fix, boolean omitBaseAnnotation) {
    return type.accept(
        new GenericTypePrettyPrintingVisitor(state, List.of(), fix, omitBaseAnnotation), null);
  }

  /**
   * Returns the name to print for {@code symbol}, a class not printed inside its enclosing type or
   * an annotation type.
   */
  private String name(Symbol.TypeSymbol symbol) {
    return fix == null
        ? symbol.getSimpleName().toString()
        : SuggestedFixes.qualifyType(state, fix, symbol);
  }

  /** Returns the name to print for the nullable annotation {@code annotationType}. */
  private String annotationName(Type annotationType) {
    if (fix != null && GenericsChecks.isSyntheticNullnessAnnotation(annotationType)) {
      return SuggestedFixes.qualifyType(state, fix, "org.jspecify.annotations.Nullable");
    }
    return name(annotationType.tsym);
  }

  /**
   * Whether {@code t}, the node being printed, is the base type: the root, or the innermost element
   * type of a root array.
   */
  private boolean isBase(Type t) {
    return !(t instanceof Type.ArrayType)
        && currentPath.steps().stream()
            .allMatch(step -> step.kind() == TypePath.Step.Kind.ARRAY_ELEMENT);
  }

  /** Brackets {@code printed} when the node being printed sits at one of the requested paths. */
  private String mark(String printed) {
    StringBuilder indices = new StringBuilder();
    for (int i = 0; i < markedPaths.size(); i++) {
      TypePath markedPath = markedPaths.get(i);
      if (markedPath != null && markedPath.reachesSameNodeAs(currentPath)) {
        indices.append(i);
      }
    }
    return indices.length() == 0
        ? printed
        : MARK_START + indices.toString() + MARK_TEXT + printed + MARK_END;
  }

  /** Strips the bracket around {@code printed}, where the whole of it is one marked node. */
  private static String withoutOuterMark(String printed) {
    int text = printed.indexOf(MARK_TEXT);
    return printed.length() > 2
            && printed.charAt(0) == MARK_START
            && text > 0
            && printed.charAt(printed.length() - 1) == MARK_END
        ? printed.substring(text + 1, printed.length() - 1)
        : printed;
  }

  /** Prints {@code child}, which {@code step} descends to from the node being printed. */
  private String descend(TypePath.Step step, Type child) {
    TypePath enclosingPath = currentPath;
    currentPath = enclosingPath.then(step);
    try {
      return child.accept(this, null);
    } finally {
      currentPath = enclosingPath;
    }
  }

  @Override
  public String visitWildcardType(Type.WildcardType t, @Nullable Void unused) {
    StringBuilder sb = new StringBuilder();
    sb.append(t.kind);
    if (t.kind != BoundKind.UNBOUND) {
      sb.append(
          descend(
              t.kind == BoundKind.EXTENDS
                  ? TypePath.Step.wildcardBound()
                  : TypePath.Step.wildcardLowerBound(),
              t.type));
    }
    return mark(sb.toString());
  }

  @Override
  public String visitClassType(Type.ClassType t, @Nullable Void s) {
    if (t.isIntersection()) {
      return mark(prettyIntersectionType((Type.IntersectionClassType) t));
    }
    if (t.tsym.isAnonymous()) {
      // eventually, we could probably do even better formatting here, but this provides all the
      // needed information for now
      return mark(t.toString());
    }
    StringBuilder sb = new StringBuilder();
    Type enclosingType = t.getEnclosingType();
    boolean printsEnclosingType = !ASTHelpers.isSameType(enclosingType, Type.noType, state);
    if (printsEnclosingType) {
      sb.append(descend(TypePath.Step.enclosingType(), enclosingType)).append('.');
    }
    appendNullableAnnotationIfPresent(t, sb);
    sb.append(printsEnclosingType ? t.tsym.getSimpleName().toString() : name(t.tsym));
    if (t.getTypeArguments().nonEmpty()) {
      sb.append('<');
      StringBuilder arguments = new StringBuilder();
      for (int i = 0; i < t.getTypeArguments().size(); i++) {
        if (i > 0) {
          arguments.append(", ");
        }
        arguments.append(
            descend(TypePath.Step.typeArgument(i, null, null), t.getTypeArguments().get(i)));
      }
      sb.append(arguments);
      sb.append(">");
    }
    return mark(sb.toString());
  }

  /**
   * Appends {@code "@Nullable "} to {@code sb} if {@code t} has a type-use {@code @Nullable}
   * annotation. We ignore all other type use annotations, including {@code @NonNull} as it is the
   * default
   */
  private void appendNullableAnnotationIfPresent(Type t, StringBuilder sb) {
    for (Attribute.TypeCompound compound : t.getAnnotationMirrors()) {
      // the annotation type is printed by a visitor of its own, so that its own path bookkeeping
      // does not place a mark inside an annotation name
      String annotName = compound.type.accept(new GenericTypePrettyPrintingVisitor(state), null);
      if ("Nullable".equals(annotName)) {
        if (!(omitBaseAnnotation && isBase(t))) {
          sb.append('@').append(annotationName(compound.type)).append(' ');
        }
        break;
      }
    }
  }

  @Override
  public String visitTypeVar(Type.TypeVar t, @Nullable Void unused) {
    StringBuilder sb = new StringBuilder();
    appendNullableAnnotationIfPresent(t, sb);
    sb.append(t.tsym.getSimpleName());
    return mark(sb.toString());
  }

  private String prettyIntersectionType(Type.IntersectionClassType t) {
    return t.getBounds().stream()
        .map(type -> ((Type) type).accept(this, null))
        .collect(joining(" & "));
  }

  @Override
  public String visitCapturedType(Type.CapturedType t, @Nullable Void s) {
    StringBuilder sb = new StringBuilder();
    appendNullableAnnotationIfPresent(t, sb);
    sb.append("capture of ");
    // no step: a captured wildcard and the wildcard it captured are one node, which is also how
    // GenericsUtils.asWildcard reads them, so the two agree on what a path addresses. One node
    // takes one bracket, and it is the outer one, which covers the annotation printed above
    sb.append(withoutOuterMark(t.wildcard.accept(this, null)));
    return mark(sb.toString());
  }

  @Override
  public String visitArrayType(Type.ArrayType t, @Nullable Void unused) {
    StringBuilder sb = new StringBuilder();
    sb.append(descend(TypePath.Step.arrayElement(), t.elemtype)).append(' ');
    appendNullableAnnotationIfPresent(t, sb);
    return mark(sb.append("[]").toString());
  }

  @Override
  public String visitErrorType(Type.ErrorType t, @Nullable Void unused) {
    // this arises for our synthetic @Nullable and @NonNull annotations; we just return the simple
    // name
    return mark(t.tsym.getSimpleName().toString());
  }

  @Override
  public String visitType(Type t, @Nullable Void s) {
    return mark(t.toString());
  }
}
