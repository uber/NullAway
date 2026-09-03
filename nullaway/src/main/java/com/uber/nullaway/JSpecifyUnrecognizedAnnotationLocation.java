/*
 * Copyright (c) 2026 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to do so, subject to the following conditions:
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

package com.uber.nullaway;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.google.errorprone.util.ErrorProneToken;
import com.google.errorprone.util.ErrorProneTokens;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.IntersectionTypeTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.util.Position;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;
import org.jspecify.annotations.Nullable;

/**
 * Reports a JSpecify nullness annotation written in a location the specification does not
 * recognize.
 *
 * <p>JSpecify defines the locations in which {@code @Nullable} and {@code @NonNull} carry meaning,
 * and states that an annotation anywhere else has none, as in {@code @Nullable int count} or {@code
 * List<@Nullable ?> values}. The specification recommends this diagnostic to tools that analyze
 * source; see <a href="https://jspecify.dev/docs/spec/#recognized-type-use">Recognized locations
 * for type-use annotations</a>.
 *
 * <p>The check reports an annotation only where it can name the unrecognized location, so a
 * construct it cannot name yields a missed diagnostic rather than a wrong one. Most of the
 * locations it names are ones the specification lists; two come from its rule that everything not
 * listed as recognized is unrecognized, namely the result type of a constructor and the root type
 * of a method reference.
 *
 * <p>The check reads {@code org.jspecify.annotations.Nullable} and {@code
 * org.jspecify.annotations.NonNull}, and no other nullness annotation, because the rule is
 * JSpecify's and JSpecify states it about its own annotations. Other tools give their own
 * {@code @Nullable} a meaning in some of these locations: the Checker Framework reads
 * {@code @Nullable} on the root type of a local variable and of a cast, and IntelliJ reads the
 * JetBrains {@code @Nullable} on a local variable.
 *
 * <p>At its default {@link SeverityLevel#SUGGESTION} severity the check reports nothing at all, so
 * a user who has not asked for it sees no diagnostics and no notes. Raise it with {@code
 * -Xep:JSpecifyUnrecognizedAnnotationLocation:WARN} or {@code :ERROR} to turn it on.
 *
 * <p>The root type of a local variable is the only location with an option of its own, and it is
 * reported by default, so that turning the check on reports every location the specification lists.
 * A codebase migrating from an annotation that does carry meaning there, such as JetBrains', may
 * want to keep those annotations in the meantime; {@code
 * -XepOpt:JSpecifyUnrecognizedAnnotationLocation:CheckLocalVariableRootType=false} leaves them
 * alone.
 */
@AutoService(BugChecker.class)
@BugPattern(
    severity = SeverityLevel.SUGGESTION,
    summary =
        "[JSpecifyUnrecognizedAnnotationLocation] JSpecify gives no meaning to a nullness"
            + " annotation in a location it does not recognize.")
public final class JSpecifyUnrecognizedAnnotationLocation extends BugChecker
    implements BugChecker.AnnotatedTypeTreeMatcher,
        BugChecker.ClassTreeMatcher,
        BugChecker.MethodTreeMatcher,
        BugChecker.NewArrayTreeMatcher,
        BugChecker.TypeParameterTreeMatcher,
        BugChecker.VariableTreeMatcher {

  /**
   * A location in which JSpecify does not recognize a nullness annotation.
   *
   * <p>Each constant carries the phrase that names the location in the diagnostic, which reads
   * {@code A nullness annotation <phrase> has no meaning under JSpecify.}
   */
  private enum UnrecognizedLocation {
    PRIMITIVE_TYPE("on a primitive type"),
    LOCAL_VARIABLE_ROOT_TYPE("on the root type of a local variable"),
    ENUM_CONSTANT("on the type of an enum constant"),
    EXCEPTION_PARAMETER("on the type of an exception parameter"),
    RECEIVER_PARAMETER("on the type of a receiver parameter", true),
    PATTERN("on a type in a pattern", true),
    /**
     * The type an {@code instanceof} operator tests against, as in {@code o instanceof @Nullable
     * String}. A type pattern that binds a variable is {@link #PATTERN} instead.
     */
    INSTANCEOF("on the type after an instanceof operator", true),
    CAST("on the root type of a cast"),
    OBJECT_CREATION("on the root type of an object creation expression"),
    ARRAY_CREATION("on the array type of an array creation expression"),
    METHOD_REFERENCE("on the root type of a method reference"),
    /**
     * A type an {@code extends} or {@code implements} clause names, as in {@code class C
     * extends @Nullable ArrayList<String>}. An anonymous class's supertype is reported as {@link
     * #OBJECT_CREATION} instead.
     */
    SUPERTYPE("on a supertype in a class declaration"),
    THROWN_TYPE("on a thrown exception type"),
    CLASS_DECLARATION("on a class declaration"),
    CONSTRUCTOR_RESULT("on the result type of a constructor"),
    TYPE_PARAMETER("directly on a type parameter declaration"),
    WILDCARD("directly on a wildcard"),
    OUTER_TYPE("on the outer type qualifying an inner type"),
    ANNOTATION_MEMBER("on the return type of an annotation interface member", true);

    private final String phrase;

    /**
     * Whether the location covers the type usages written inside it, so that a nullness annotation
     * anywhere within the location is unrecognized too.
     *
     * <p>A location that does not cover them reaches its own root type and stops, which leaves the
     * type argument in {@code (List<@Nullable String>) o} recognized. Compare {@code
     * (List<@Nullable ?>) o}, reported as a wildcard because a cast stops short of it, with {@code
     * o instanceof List<@Nullable ?>}, reported as the {@code instanceof} operand because JSpecify
     * recognizes no type usage there at all.
     */
    private final boolean coversNestedTypes;

    UnrecognizedLocation(String phrase) {
      this(phrase, false);
    }

    UnrecognizedLocation(String phrase, boolean coversNestedTypes) {
      this.phrase = phrase;
      this.coversNestedTypes = coversNestedTypes;
    }
  }

  /**
   * An unrecognized location that a nullness annotation was written in, and the tree a fix is built
   * against.
   *
   * @param anchor the type usage the annotation lands on, or the qualified name as a whole where
   *     the annotation lands on one of its qualifiers
   */
  private record TypeUsage(UnrecognizedLocation location, Tree anchor) {}

  /** The nullness annotations JSpecify defines, which are the only ones this check reads. */
  private static final ImmutableSet<String> JSPECIFY_NULLNESS_ANNOTATIONS =
      ImmutableSet.of("org.jspecify.annotations.Nullable", "org.jspecify.annotations.NonNull");

  /**
   * Name of the Error Prone flag that turns off reporting for {@link
   * UnrecognizedLocation#LOCAL_VARIABLE_ROOT_TYPE}. It carries the check's own name rather than
   * NullAway's, since the check reads no NullAway configuration.
   */
  private static final String FL_CHECK_LOCAL_VARIABLE_ROOT_TYPE =
      "JSpecifyUnrecognizedAnnotationLocation:CheckLocalVariableRootType";

  /** Whether an annotation on the root type of a local variable is reported. True by default. */
  private final boolean checkLocalVariableRootType;

  /**
   * Creates a check with default options. Error Prone requires this constructor in addition to the
   * one taking an {@link ErrorProneFlags} object.
   */
  public JSpecifyUnrecognizedAnnotationLocation() {
    checkLocalVariableRootType = true;
  }

  @Inject // For future Error Prone versions in which checkers are loaded using Guice
  public JSpecifyUnrecognizedAnnotationLocation(ErrorProneFlags flags) {
    checkLocalVariableRootType = flags.getBoolean(FL_CHECK_LOCAL_VARIABLE_ROOT_TYPE).orElse(true);
  }

  @Override
  public Description matchAnnotatedType(AnnotatedTypeTree tree, VisitorState state) {
    if (!isEnabled(state)) {
      return Description.NO_MATCH;
    }
    ImmutableList<AnnotationTree> annotations = nullnessAnnotationsIn(tree.getAnnotations());
    if (annotations.isEmpty()) {
      return Description.NO_MATCH;
    }
    TypeUsage usage = classifyTypeUsage(tree, state);
    if (usage != null) {
      report(annotations, usage.location(), usage.anchor(), state);
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    if (!isEnabled(state)) {
      return Description.NO_MATCH;
    }
    TreePath parent = state.getPath().getParentPath();
    if (parent != null && isCompactConstructorParameter(parent.getLeaf(), tree)) {
      return Description.NO_MATCH;
    }
    checkAnnotationsOnDeclaration(
        tree.getModifiers().getAnnotations(), tree.getType(), variableLocation(tree), state);
    return Description.NO_MATCH;
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    if (!isEnabled(state)) {
      return Description.NO_MATCH;
    }
    // Only a constructor has no return type tree.  JSpecify recognizes a method's return type but
    // lists no result type for a constructor.
    Tree returnType = tree.getReturnType();
    UnrecognizedLocation declarationLocation;
    if (returnType == null) {
      declarationLocation = UnrecognizedLocation.CONSTRUCTOR_RESULT;
    } else {
      declarationLocation =
          isAnnotationInterfaceMember(tree) ? UnrecognizedLocation.ANNOTATION_MEMBER : null;
    }
    checkAnnotationsOnDeclaration(
        tree.getModifiers().getAnnotations(), returnType, declarationLocation, state);
    return Description.NO_MATCH;
  }

  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    if (!isEnabled(state)) {
      return Description.NO_MATCH;
    }
    checkAnnotationsOnDeclaration(
        tree.getModifiers().getAnnotations(), null, UnrecognizedLocation.CLASS_DECLARATION, state);
    return Description.NO_MATCH;
  }

  @Override
  public Description matchTypeParameter(TypeParameterTree tree, VisitorState state) {
    if (!isEnabled(state)) {
      return Description.NO_MATCH;
    }
    report(
        nullnessAnnotationsIn(tree.getAnnotations()),
        UnrecognizedLocation.TYPE_PARAMETER,
        tree,
        state);
    return Description.NO_MATCH;
  }

  @Override
  public Description matchNewArray(NewArrayTree tree, VisitorState state) {
    if (!isEnabled(state)) {
      return Description.NO_MATCH;
    }
    // javac records the annotations of an array creation in one of two places.  Given dimension
    // expressions, as in `new String @Nullable [5] @NonNull [3]`, it splits them by dimension from
    // the created array outwards, and only the first list annotates the array itself.  Given an
    // initializer, as in `new String @Nullable [] {"x"}`, there are no dimensions to split by: the
    // created array's annotations are the tree's own, and any inner dimension's annotations have
    // moved into the element type.  Either way, the rest annotate component types, and JSpecify
    // recognizes those.
    List<? extends List<? extends AnnotationTree>> dimensionAnnotations = tree.getDimAnnotations();
    List<? extends AnnotationTree> arrayAnnotations =
        dimensionAnnotations.isEmpty() ? tree.getAnnotations() : dimensionAnnotations.get(0);
    report(
        nullnessAnnotationsIn(arrayAnnotations), UnrecognizedLocation.ARRAY_CREATION, tree, state);
    return Description.NO_MATCH;
  }

  /**
   * Reports each nullness annotation in a declaration's modifiers that lands in an unrecognized
   * location. Such an annotation lands on the declared type, with two exceptions that {@link
   * #modifierAnnotationLocation} reads off that type: array dimensions are stripped, so an
   * annotation before {@code String[] names} annotates {@code String}; and the outermost type in a
   * qualified name takes the annotation, so one before {@code Outer.Inner} annotates {@code Outer}.
   *
   * @param annotations every annotation in the declaration's modifiers, not only the nullness ones
   * @param typeTree the declared type, or {@code null} for a declaration that has none
   * @param declarationLocation the location to report if the declared root type is unrecognized, or
   *     {@code null} if the declaration is a recognized location
   */
  private void checkAnnotationsOnDeclaration(
      List<? extends AnnotationTree> annotations,
      @Nullable Tree typeTree,
      @Nullable UnrecognizedLocation declarationLocation,
      VisitorState state) {
    ImmutableList<AnnotationTree> nullnessAnnotations = nullnessAnnotationsIn(annotations);
    if (nullnessAnnotations.isEmpty()) {
      return;
    }
    UnrecognizedLocation location = modifierAnnotationLocation(typeTree, declarationLocation);
    if (location != null) {
      report(nullnessAnnotations, location, typeTree, state);
    }
  }

  /**
   * Returns the location of an annotation written in the modifiers of a declaration whose declared
   * type is {@code typeTree}, or {@code null} if that location is recognized.
   */
  private static @Nullable UnrecognizedLocation modifierAnnotationLocation(
      @Nullable Tree typeTree, @Nullable UnrecognizedLocation declarationLocation) {
    if (typeTree == null) {
      return declarationLocation;
    }
    // In `@Nullable String[] names` the annotation applies to the component type rather than to the
    // array, so strip the array dimensions to find the type usage it lands on.
    Tree rootType = typeTree;
    boolean onArrayComponent = false;
    while (true) {
      if (rootType instanceof AnnotatedTypeTree annotatedType) {
        rootType = annotatedType.getUnderlyingType();
      } else if (rootType instanceof ArrayTypeTree arrayType) {
        rootType = arrayType.getType();
        onArrayComponent = true;
      } else {
        break;
      }
    }
    if (rootType instanceof PrimitiveTypeTree) {
      return UnrecognizedLocation.PRIMITIVE_TYPE;
    }
    if (onArrayComponent
        && (declarationLocation == null || !declarationLocation.coversNestedTypes)) {
      // The annotation lands on the array component type, a recognized location even where the
      // declaration's own root type is not.  Only a qualified inner type is left to report: in
      // `@Nullable Outer.Inner[] x` it lands on `Outer`, and the author meant
      // `Outer.@Nullable Inner[] x`.
      return qualifiedInnerType(rootType) != null ? UnrecognizedLocation.OUTER_TYPE : null;
    }
    if (declarationLocation != null) {
      return declarationLocation;
    }
    return qualifiedInnerType(rootType) != null ? UnrecognizedLocation.OUTER_TYPE : null;
  }

  /**
   * Returns the location of a nullness annotation written directly on the type usage {@code tree},
   * together with the tree a fix is built against. Call it only where {@code tree} carries such an
   * annotation.
   *
   * <p>Neither answer can be read off {@code tree}, so the method walks {@link
   * VisitorState#getPath} outwards to the declaration or expression the type usage belongs to. The
   * walk settles two questions: whether the annotation stands on that construct's root type or
   * below it, and, where the type usage and the construct around it are both unrecognized, which
   * of the two names the annotation's position better and so gives the fix its target.
   *
   * <p>Returns {@code null} in two cases. The location is recognized, as the type argument in
   * {@code (List<@Nullable String>) o} is. Or javac has put one subtree under two parents, which
   * Error Prone scans once per parent, so the same annotation arrives here through two {@link
   * TreePath}s and the walk from the other parent reports it. An anonymous class body's supertype
   * tree is the tree the enclosing {@code new} expression names, and a compact constructor's
   * parameters are javac's copies of the record components, sharing their annotation trees.
   */
  private static @Nullable TypeUsage classifyTypeUsage(AnnotatedTypeTree tree, VisitorState state) {
    Tree underlyingType = tree.getUnderlyingType();
    // A primitive and a wildcard are unrecognized wherever they are written, but the walk still
    // runs before either is returned.  The walk recognizes a subtree javac reached through two
    // parents, and that case returns null so the other walk reports the annotation.
    UnrecognizedLocation onTypeItself =
        underlyingType instanceof PrimitiveTypeTree
            ? UnrecognizedLocation.PRIMITIVE_TYPE
            : underlyingType instanceof WildcardTree ? UnrecognizedLocation.WILDCARD : null;
    // Walk out to the declaration or expression the type usage belongs to.  Crossing a type
    // argument, an array component, or a bound puts the annotation below the root type of whatever
    // construct is reached next, on a type usage JSpecify recognizes in its own right, and
    // belowRootType records that.  qualifierAnchor records the qualified name the annotation stands
    // in front of, which is where a fix moves it.
    Tree qualifierAnchor = null;
    boolean belowRootType = false;
    Tree child = tree;
    for (TreePath path = state.getPath().getParentPath();
        path != null;
        path = path.getParentPath()) {
      Tree parent = path.getLeaf();
      if (parent instanceof ParameterizedTypeTree parameterizedType) {
        belowRootType |= parameterizedType.getTypeArguments().contains(child);
      } else if (parent instanceof ArrayTypeTree
          || parent instanceof WildcardTree
          || parent instanceof TypeParameterTree) {
        belowRootType = true;
      } else if (parent instanceof AnnotatedTypeTree) {
        // The enclosing annotated type reports its own annotations.  Where it wraps the qualified
        // name that the fix writes into, it carries the annotations already on that name, so the
        // anchor moves out to it and destinationIsOccupied() can see them.
        if (Objects.equals(child, qualifierAnchor)) {
          qualifierAnchor = parent;
        }
      } else if (parent instanceof UnionTypeTree || parent instanceof IntersectionTypeTree) {
        // Crossing into a union or an intersection stays on a root type: each alternative of
        // `catch (@Nullable A | B e)` is a root type of the exception parameter, and each member of
        // `(@Nullable A & B) o` is a root type of the cast.  Both are reported as that construct.
      } else if (parent instanceof ClassTree classTree
          && classTree.getSimpleName().isEmpty()
          && isSupertype(classTree, child)) {
        // An anonymous class body's supertype tree is the tree the enclosing `new` expression
        // names, so this path is a second view of an annotation the NewClassTree path reports.
        return null;
      } else if (parent instanceof MemberSelectTree memberSelect
          && Objects.equals(memberSelect.getExpression(), child)) {
        if (!belowRootType) {
          // The outermost qualified name is the anchor, so `@Nullable P.Inner.Innermost` moves the
          // annotation to `Innermost` rather than to `Inner`, which qualifies it in turn.  Within a
          // type argument the annotation lands on that argument rather than on a qualifier, as in
          // `P.Generic<@Nullable String>.Deep`, so nothing is recorded.
          qualifierAnchor = memberSelect;
        }
      } else if (parent instanceof VariableTree
          && path.getParentPath() != null
          && isCompactConstructorParameter(path.getParentPath().getLeaf(), parent)) {
        // The compact constructor's parameters are javac's copies of the record components, so
        // this path is a second view of an annotation the component itself reports.
        return null;
      } else {
        // The construct takes the annotation, unless the annotation stands below its root type
        // and the construct stops there.  A primitive is the exception that always keeps its own
        // phrase: `(@Nullable int) x` reads as a primitive rather than as a cast, and no fix moves
        // the annotation either way.  The construct replaces a wildcard, because the wildcard's own
        // fix moves the annotation to a bound: `(List<@Nullable ?>) o` reports the wildcard and
        // gets that fix, while `o instanceof List<@Nullable ?>` reports the operand and removes it.
        UnrecognizedLocation enclosing = enclosingLocation(parent, child);
        if (onTypeItself != UnrecognizedLocation.PRIMITIVE_TYPE
            && enclosing != null
            && (enclosing.coversNestedTypes || !belowRootType)) {
          return new TypeUsage(enclosing, underlyingType);
        }
        break;
      }
      child = parent;
    }
    if (qualifierAnchor != null) {
      // The outer type is reported only where no construct took the annotation first.  So
      // `(@Nullable Outer.Inner) o` is reported as a cast, and the field
      // `List<@Nullable Outer.Inner> f`, whose construct takes nothing, as an outer type.
      return new TypeUsage(UnrecognizedLocation.OUTER_TYPE, qualifierAnchor);
    }
    return onTypeItself == null ? null : new TypeUsage(onTypeItself, underlyingType);
  }

  /**
   * Returns the location of the root type {@code child} names within {@code construct}. Returns
   * {@code null} where that location is recognized, and where {@code construct} is a kind the check
   * does not classify.
   *
   * @param construct the declaration or expression that holds the type usage
   * @param child the child of {@code construct} the type usage was reached through
   */
  private static @Nullable UnrecognizedLocation enclosingLocation(Tree construct, Tree child) {
    if (construct instanceof VariableTree variable) {
      return variableLocation(variable);
    }
    if (construct instanceof MethodTree method) {
      if (Objects.equals(child, method.getReturnType())) {
        return isAnnotationInterfaceMember(method) ? UnrecognizedLocation.ANNOTATION_MEMBER : null;
      }
      return method.getThrows().contains(child) ? UnrecognizedLocation.THROWN_TYPE : null;
    }
    if (construct instanceof ClassTree classTree) {
      return isSupertype(classTree, child) ? UnrecognizedLocation.SUPERTYPE : null;
    }
    if (construct instanceof TypeCastTree) {
      return UnrecognizedLocation.CAST;
    }
    if (construct instanceof InstanceOfTree) {
      return UnrecognizedLocation.INSTANCEOF;
    }
    if (construct instanceof NewClassTree newClass) {
      return Objects.equals(child, newClass.getIdentifier())
          ? UnrecognizedLocation.OBJECT_CREATION
          : null;
    }
    if (construct instanceof MemberReferenceTree reference) {
      // A method reference's qualifier expression is its root type, as in `@Nullable String::new`.
      // The location does not cover what is nested inside it, so `ArrayList<@Nullable String>::new`
      // stays recognized.
      return Objects.equals(child, reference.getQualifierExpression())
          ? UnrecognizedLocation.METHOD_REFERENCE
          : null;
    }
    // A component type of an array creation expression is recognized, and every other construct is
    // left to a future revision of this check.
    return null;
  }

  /**
   * Returns whether {@code variable} is one of the parameters javac copies from a record's
   * components into its compact constructor.
   *
   * <p>The copies keep the components' source positions and share their annotation trees, so each
   * is a second view of a declaration already reported. The parameter list they sit in tells them
   * apart from an ordinary declaration, not their own symbol kind: a lambda parameter written in
   * the constructor's body is an {@link ElementKind#PARAMETER} owned by that same constructor, and
   * its annotations are the author's own.
   *
   * @param parent the tree {@code variable} is declared under
   */
  private static boolean isCompactConstructorParameter(@Nullable Tree parent, Tree variable) {
    if (!(parent instanceof MethodTree method) || !method.getParameters().contains(variable)) {
      return false;
    }
    Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(method);
    return symbol != null && (symbol.flags() & Flags.COMPACT_RECORD_CONSTRUCTOR) != 0;
  }

  /** Returns whether {@code child} is a type {@code classTree} extends or implements. */
  private static boolean isSupertype(ClassTree classTree, Tree child) {
    return Objects.equals(child, classTree.getExtendsClause())
        || classTree.getImplementsClause().contains(child);
  }

  /**
   * Returns the location of {@code variable}'s root type, or {@code null} for a declaration whose
   * root type JSpecify recognizes.
   */
  private static @Nullable UnrecognizedLocation variableLocation(VariableTree variable) {
    if (variable.getNameExpression() != null) {
      // Only a receiver parameter has a name expression, as in `void m(Foo this)`.
      return UnrecognizedLocation.RECEIVER_PARAMETER;
    }
    Symbol symbol = ASTHelpers.getSymbol(variable);
    if (symbol == null) {
      return null;
    }
    return switch (symbol.getKind()) {
      case LOCAL_VARIABLE, RESOURCE_VARIABLE -> UnrecognizedLocation.LOCAL_VARIABLE_ROOT_TYPE;
      case EXCEPTION_PARAMETER -> UnrecognizedLocation.EXCEPTION_PARAMETER;
      case BINDING_VARIABLE -> UnrecognizedLocation.PATTERN;
      case ENUM_CONSTANT -> UnrecognizedLocation.ENUM_CONSTANT;
      // Every other kind is a recognized location: a field, a parameter, or a record component.
      default -> null;
    };
  }

  /** Returns whether {@code method} is declared in an annotation interface. */
  private static boolean isAnnotationInterfaceMember(MethodTree method) {
    Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(method);
    return symbol != null && symbol.enclClass().getKind() == ElementKind.ANNOTATION_TYPE;
  }

  /**
   * Returns the member select through which {@code typeTree} names a qualified type, as in {@code
   * Outer.Inner}, {@code Map.Entry} or {@code java.util.List}; or {@code null} if {@code typeTree}
   * names a type without a qualifier.
   *
   * <p>Array dimensions, type arguments and annotations are stripped first, and repeatedly: {@code
   * Outer.Inner[][]} names the same type as {@code Outer.Inner}.
   *
   * <p>An annotation on the type has to be <em>written</em> before the member select's last part.
   * Before its first part the annotation either annotates a different type, as {@code @Nullable
   * Outer.Inner} annotates {@code Outer}, or javac rejects it outright, as it does {@code @Nullable
   * java.util.List}. {@link #qualifiedInnerType} decides the narrower question of where an
   * annotation already written lands.
   */
  private static @Nullable MemberSelectTree selectedTypeName(Tree typeTree) {
    Tree type = typeTree;
    while (true) {
      if (type instanceof AnnotatedTypeTree annotatedType) {
        type = annotatedType.getUnderlyingType();
      } else if (type instanceof ArrayTypeTree arrayType) {
        type = arrayType.getType();
      } else if (type instanceof ParameterizedTypeTree parameterizedType) {
        type = parameterizedType.getType();
      } else {
        break;
      }
    }
    return type instanceof MemberSelectTree memberSelect
            && ASTHelpers.getSymbol(type) instanceof Symbol.ClassSymbol
        ? memberSelect
        : null;
  }

  /**
   * Returns the member select through which {@code typeTree} names an inner class from its
   * enclosing class, or {@code null} where {@code typeTree} names no such class.
   *
   * <p>An annotation written before the whole name lands on that enclosing class.
   */
  private static @Nullable MemberSelectTree qualifiedInnerType(Tree typeTree) {
    MemberSelectTree memberSelect = selectedTypeName(typeTree);
    if (memberSelect == null) {
      return null;
    }
    // Only an inner type has an enclosing type for an annotation to land on.  A package or a
    // static member type merely scopes the name, so this method returns null for both
    // `@Nullable Map.Entry` and `@Nullable java.util.List`.  Whether javac accepts either
    // spelling varies by position, and this method does not decide that.
    Symbol qualifier = ASTHelpers.getSymbol(memberSelect.getExpression());
    return qualifier instanceof Symbol.ClassSymbol
            && !ASTHelpers.isStatic(ASTHelpers.getSymbol(memberSelect))
        ? memberSelect
        : null;
  }

  /** Returns the JSpecify nullness annotations among {@code annotations}. */
  private static ImmutableList<AnnotationTree> nullnessAnnotationsIn(
      List<? extends AnnotationTree> annotations) {
    return annotations.stream()
        .filter(JSpecifyUnrecognizedAnnotationLocation::isNullnessAnnotation)
        .collect(toImmutableList());
  }

  /** Returns whether {@code annotation} is one of the nullness annotations JSpecify defines. */
  private static boolean isNullnessAnnotation(AnnotationTree annotation) {
    Symbol symbol = ASTHelpers.getSymbol(annotation.getAnnotationType());
    return symbol instanceof Symbol.ClassSymbol
        && JSPECIFY_NULLNESS_ANNOTATIONS.contains(symbol.getQualifiedName().toString());
  }

  /**
   * Reports each annotation at {@code location}, with a fix that puts the annotation where the
   * author meant it or removes it when no such place exists.
   *
   * <p>Reports nothing at {@link UnrecognizedLocation#LOCAL_VARIABLE_ROOT_TYPE} when {@link
   * #checkLocalVariableRootType} is false.
   *
   * @param anchor the tree a fix is built against, or {@code null} where only removal is offered
   */
  private void report(
      ImmutableList<AnnotationTree> annotations,
      UnrecognizedLocation location,
      @Nullable Tree anchor,
      VisitorState state) {
    if (annotations.isEmpty()
        || (location == UnrecognizedLocation.LOCAL_VARIABLE_ROOT_TYPE
            && !checkLocalVariableRootType)) {
      return;
    }
    for (AnnotationTree annotation : annotations) {
      Description.Builder description =
          buildDescription(annotation)
              .setMessage(
                  "A nullness annotation " + location.phrase + " has no meaning under JSpecify.");
      SuggestedFix fix = buildFix(annotation, location, anchor, annotations.size(), state);
      if (!fix.isEmpty()) {
        description.addFix(fix);
      }
      state.reportMatch(description.build());
    }
  }

  /**
   * Returns a fix that moves {@code annotation} to the recognized location the author meant, or one
   * that only removes it when no such location can be named unambiguously.
   *
   * @param annotationCount how many nullness annotations share {@code anchor}; a move is ambiguous
   *     once there is more than one
   */
  private static SuggestedFix buildFix(
      AnnotationTree annotation,
      UnrecognizedLocation location,
      @Nullable Tree anchor,
      int annotationCount,
      VisitorState state) {
    String source = state.getSourceForNode(annotation);
    SuggestedFix.Builder fix = deleteAnnotation(annotation, state);
    if (source == null || anchor == null || annotationCount > 1) {
      return fix.build();
    }
    return switch (location) {
      case WILDCARD -> moveToWildcardBound(fix, (WildcardTree) anchor, annotation, source, state);
      case TYPE_PARAMETER ->
          moveToTypeParameterBound(fix, (TypeParameterTree) anchor, annotation, source, state);
      case OUTER_TYPE -> moveToSelectedName(fix, anchor, annotation, source, state);
      default -> fix.build();
    };
  }

  /**
   * Returns a fix that removes {@code annotation} together with the spaces and tabs separating it
   * from whatever follows it on the line.
   *
   * <p>Those blanks belong to the annotation: leaving them turns {@code (@Nullable T) o} into
   * {@code ( T) o}, and gives a line that began with the annotation a column of indentation. A
   * newline is left alone, so an annotation written on a line of its own leaves that line empty
   * rather than joining it to the next.
   */
  private static SuggestedFix.Builder deleteAnnotation(
      AnnotationTree annotation, VisitorState state) {
    int start = ASTHelpers.getStartPosition(annotation);
    int end = state.getEndPosition(annotation);
    CharSequence source = state.getSourceCode();
    if (start == Position.NOPOS || end == Position.NOPOS || source == null) {
      return SuggestedFix.builder().delete(annotation);
    }
    while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
      end++;
    }
    return SuggestedFix.builder().replace(start, end, "");
  }

  /**
   * Returns {@code fix} with {@code annotationSource} written on the type {@code bound} names. That
   * position is not always the start of {@code bound}'s source text: {@code @Nullable String[]}
   * annotates the component rather than the array, and {@code @Nullable Outer.Inner} annotates the
   * qualifier rather than the inner type. {@code fix} comes back unchanged where a nullness
   * annotation already stands at that position; see {@link #destinationIsOccupied}.
   */
  private static SuggestedFix.Builder annotateBound(
      SuggestedFix.Builder fix,
      Tree bound,
      AnnotationTree moving,
      String annotationSource,
      VisitorState state) {
    Tree component = bound;
    boolean onArray = false;
    boolean arrayIsAnnotated = false;
    while (true) {
      if (component instanceof AnnotatedTypeTree annotatedType) {
        // An AnnotatedTypeTree that the walk reaches before it descends into an array annotates
        // the array itself, as in `String @NonNull []`. One that it reaches afterwards annotates a
        // component type, which is a type usage of its own.
        arrayIsAnnotated |=
            !onArray && !nullnessAnnotationsIn(annotatedType.getAnnotations()).isEmpty();
        component = annotatedType.getUnderlyingType();
      } else if (component instanceof ArrayTypeTree arrayType) {
        component = arrayType.getType();
        onArray = true;
      } else {
        break;
      }
    }
    if (onArray) {
      if (arrayIsAnnotated) {
        return fix;
      }
      // The outermost array is annotated after the element type, as in `String @Nullable [][]`.
      // Every ArrayTypeTree in `String[][]` reports the range of the whole type, so the element
      // type supplies the offset.
      int afterComponent = state.getEndPosition(component);
      return fix.replace(afterComponent, afterComponent, " " + annotationSource);
    }
    if (destinationIsOccupied(bound, moving)) {
      return fix;
    }
    MemberSelectTree memberSelect = selectedTypeName(bound);
    if (memberSelect == null) {
      return fix.prefixWith(bound, annotationSource + " ");
    }
    int nameStart = selectedNameStart(memberSelect, state);
    if (nameStart == Position.NOPOS) {
      return fix;
    }
    return fix.replace(nameStart, nameStart, annotationSource + " ");
  }

  /**
   * Returns whether a nullness annotation already stands where a move would write {@code moving}.
   *
   * <p>A move writes on the last name of a qualified type name, or on the type name itself where
   * there is no qualifier. Every nullness annotation on a qualifier is relocated to that one
   * position, so the one written closest to it is the one that arrives and every other is deleted.
   * Only an annotation that the move would pass is therefore an obstacle, and {@code moving} is
   * where the walk stops.
   *
   * <p>{@code moving} is outside {@code typeTree} altogether where it was written in a
   * declaration's modifiers, or on the wildcard or type parameter that {@code typeTree} bounds. The
   * walk then reaches no stopping point, and every annotation in the chain is an obstacle.
   */
  private static boolean destinationIsOccupied(Tree typeTree, AnnotationTree moving) {
    Tree type = typeTree;
    while (true) {
      if (type instanceof AnnotatedTypeTree annotatedType) {
        if (annotatedType.getAnnotations().contains(moving)) {
          return false;
        }
        // An AnnotatedTypeTree wrapping an array annotates the array rather than the name written
        // inside it, as in `Outer.Inner @NonNull []`, so it is not an obstacle.
        if (!(annotatedType.getUnderlyingType() instanceof ArrayTypeTree)
            && !nullnessAnnotationsIn(annotatedType.getAnnotations()).isEmpty()) {
          return true;
        }
        type = annotatedType.getUnderlyingType();
      } else if (type instanceof ArrayTypeTree arrayType) {
        type = arrayType.getType();
      } else if (type instanceof ParameterizedTypeTree parameterizedType) {
        type = parameterizedType.getType();
      } else if (type instanceof MemberSelectTree memberSelect) {
        type = memberSelect.getExpression();
      } else {
        return false;
      }
    }
  }

  /**
   * Returns the offset {@code memberSelect}'s last name starts at, which is where an annotation on
   * the type it names goes; or {@link Position#NOPOS} if the source does not yield one.
   *
   * <p>The offset is read off the tokens rather than computed from the name's length, because a
   * name may be written with Unicode escapes, which javac decodes before it lexes: {@code Inner}
   * spelled with an escape for its {@code e} is ten characters of source for a five-character name,
   * so subtracting the name's length from the end position lands inside the escape.
   *
   * <p>A member select's source ends at its own name, so the last token carrying a name is that
   * name whatever precedes it. What precedes it can be a comment or an annotation written between
   * the dot and the name, an annotation argument that is itself an identifier, or a keyword javac
   * tags as named, such as the {@code int} of {@code @Marker(type = int.class)}; a later token
   * overwrites each of them.
   *
   * <p>The tokens come from {@link ErrorProneTokens} rather than from {@link
   * VisitorState#getOffsetTokensForNode}, whose return type changed from {@code List} to {@code
   * ImmutableList} after the oldest Error Prone release NullAway supports. NullAway compiles
   * against the newest, so a call to it would throw {@link NoSuchMethodError} on the oldest.
   */
  private static int selectedNameStart(MemberSelectTree memberSelect, VisitorState state) {
    int offset = ASTHelpers.getStartPosition(memberSelect);
    String source = state.getSourceForNode(memberSelect);
    if (offset == Position.NOPOS || source == null) {
      return Position.NOPOS;
    }
    int start = Position.NOPOS;
    for (ErrorProneToken token : ErrorProneTokens.getTokens(source, offset, state.context)) {
      if (token.hasName()) {
        start = token.pos();
      }
    }
    return start;
  }

  /**
   * Rewrites a wildcard so that the annotation lands on its upper bound.
   *
   * <p>{@code Foo<@Nullable ?>} becomes {@code Foo<? extends @Nullable Object>}, and {@code
   * Foo<@Nullable ? extends Bar>} becomes {@code Foo<? extends @Nullable Bar>}.
   */
  private static SuggestedFix moveToWildcardBound(
      SuggestedFix.Builder fix,
      WildcardTree wildcard,
      AnnotationTree moving,
      String annotationSource,
      VisitorState state) {
    return switch (wildcard.getKind()) {
      case EXTENDS_WILDCARD ->
          annotateBound(fix, wildcard.getBound(), moving, annotationSource, state).build();
      case UNBOUNDED_WILDCARD ->
          fix.postfixWith(wildcard, " extends " + annotationSource + " Object").build();
      // A lower-bounded wildcard has no upper bound to write the annotation on.
      default -> fix.build();
    };
  }

  /**
   * Rewrites a type parameter so that the annotation lands on its bound.
   *
   * <p>{@code <@Nullable T>} becomes {@code <T extends @Nullable Object>}, and {@code <@Nullable T
   * extends Bar>} becomes {@code <T extends @Nullable Bar>}.
   */
  private static SuggestedFix moveToTypeParameterBound(
      SuggestedFix.Builder fix,
      TypeParameterTree typeParameter,
      AnnotationTree moving,
      String annotationSource,
      VisitorState state) {
    List<? extends Tree> bounds = typeParameter.getBounds();
    if (bounds.isEmpty()) {
      return fix.postfixWith(typeParameter, " extends " + annotationSource + " Object").build();
    }
    if (bounds.size() > 1) {
      // An intersection bound has no single member that the annotation belongs on, and a fix that
      // annotated every member would add annotations the author did not write.
      return fix.build();
    }
    return annotateBound(fix, bounds.get(0), moving, annotationSource, state).build();
  }

  /** Rewrites {@code @Nullable Outer.Inner} as {@code Outer.@Nullable Inner}. */
  private static SuggestedFix moveToSelectedName(
      SuggestedFix.Builder fix,
      Tree typeTree,
      AnnotationTree moving,
      String annotationSource,
      VisitorState state) {
    MemberSelectTree memberSelect = selectedTypeName(typeTree);
    if (memberSelect == null || destinationIsOccupied(typeTree, moving)) {
      return fix.build();
    }
    int nameStart = selectedNameStart(memberSelect, state);
    if (nameStart == Position.NOPOS) {
      return fix.build();
    }
    // Rewriting the whole member select would drop any other annotation written on it, and would
    // overlap this fix's own deletion where the annotation being moved sits inside the qualifier.
    return fix.replace(nameStart, nameStart, annotationSource + " ").build();
  }

  /**
   * Returns whether the check reports at the severity it is running under.
   *
   * <p>The check reports nothing at {@link SeverityLevel#SUGGESTION}, its default, and reports at
   * every other severity. JSpecify recommends that tools offer this diagnostic as an option, and
   * Error Prone cannot ship a plugin check turned off, so this default is as close to off as the
   * check can get.
   */
  private boolean isEnabled(VisitorState state) {
    return !SeverityLevel.SUGGESTION.equals(state.severityMap().get(canonicalName()));
  }

  @Override
  public String linkUrl() {
    return "https://jspecify.dev/docs/spec/#recognized-type-use";
  }
}
