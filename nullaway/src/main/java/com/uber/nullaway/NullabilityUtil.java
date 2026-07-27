/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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

package com.uber.nullaway;

import static com.sun.source.tree.Tree.Kind.OTHER;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.JCDiagnostic;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.nullaway.javacutil.AnnotationUtils;
import org.jspecify.annotations.Nullable;

/** Helpful utility methods for nullability analysis. */
public class NullabilityUtil {
  public static final String NULLMARKED_SIMPLE_NAME = "NullMarked";
  public static final String NULLUNMARKED_SIMPLE_NAME = "NullUnmarked";

  private static final Supplier<Type> MAP_TYPE_SUPPLIER = Suppliers.typeFromString("java.util.Map");
  private static final String JETBRAINS_NOT_NULL = "org.jetbrains.annotations.NotNull";

  private NullabilityUtil() {}

  /**
   * finds the corresponding functional interface method for a lambda expression or method reference
   *
   * @param tree the lambda expression or method reference
   * @return the functional interface method
   */
  public static Symbol.MethodSymbol getFunctionalInterfaceMethod(ExpressionTree tree, Types types) {
    Preconditions.checkArgument(
        (tree instanceof LambdaExpressionTree) || (tree instanceof MemberReferenceTree));
    Type funcInterfaceType = ((JCTree.JCFunctionalExpression) tree).type;
    return (Symbol.MethodSymbol) types.findDescriptorSymbol(funcInterfaceType.tsym);
  }

  /**
   * determines whether a lambda parameter is missing an explicit type declaration
   *
   * @param lambdaParameter the parameter
   * @return true if there is no type declaration, false otherwise
   */
  public static boolean lambdaParamIsImplicitlyTyped(VariableTree lambdaParameter) {
    // kind of a hack; the "preferred position" seems to be the position
    // of the variable name.  if this differs from the start position, it
    // means there is an explicit type declaration
    JCDiagnostic.DiagnosticPosition diagnosticPosition =
        (JCDiagnostic.DiagnosticPosition) lambdaParameter;
    return diagnosticPosition.getStartPosition() == diagnosticPosition.getPreferredPosition();
  }

  /**
   * find the closest ancestor method in a superclass or superinterface that method overrides
   *
   * @param method the subclass method
   * @param types the types data structure from javac
   * @return closest overridden ancestor method, or <code>null</code> if method does not override
   *     anything
   */
  public static Symbol.@Nullable MethodSymbol getClosestOverriddenMethod(
      Symbol.MethodSymbol method, Types types) {
    // taken from Error Prone MethodOverrides check
    Symbol.ClassSymbol owner = method.enclClass();
    for (Type s : types.closure(owner.type)) {
      if (types.isSameType(s, owner.type)) {
        continue;
      }
      for (Symbol m : s.tsym.members().getSymbolsByName(method.name)) {
        if (!(m instanceof Symbol.MethodSymbol msym)) {
          continue;
        }
        if (msym.isStatic()) {
          continue;
        }
        if (method.overrides(msym, owner, types, /*checkReturn*/ false)) {
          return msym;
        }
      }
    }
    return null;
  }

  /**
   * find the enclosing method, lambda expression or initializer block for the leaf of some tree
   * path
   *
   * @param path the tree path
   * @param others also stop and return in case of any of these tree kinds
   * @return the closest enclosing method / lambda
   */
  public static @Nullable TreePath findEnclosingMethodOrLambdaOrInitializer(
      TreePath path, ImmutableSet<Tree.Kind> others) {
    TreePath curPath = path.getParentPath();
    while (curPath != null) {
      if (curPath.getLeaf() instanceof MethodTree
          || curPath.getLeaf() instanceof LambdaExpressionTree
          || others.contains(curPath.getLeaf().getKind())) {
        return curPath;
      }
      TreePath parent = curPath.getParentPath();
      if (parent != null && parent.getLeaf() instanceof ClassTree) {
        if (curPath.getLeaf() instanceof BlockTree) {
          // found initializer block
          return curPath;
        }
        if (curPath.getLeaf() instanceof VariableTree
            && ((VariableTree) curPath.getLeaf()).getInitializer() != null) {
          // found field with an inline initializer
          return curPath;
        }
      }
      curPath = parent;
    }
    return null;
  }

  /**
   * find the enclosing method, lambda expression or initializer block for the leaf of some tree
   * path
   *
   * @param path the tree path
   * @return the closest enclosing method / lambda
   */
  public static @Nullable TreePath findEnclosingMethodOrLambdaOrInitializer(TreePath path) {
    return findEnclosingMethodOrLambdaOrInitializer(path, ImmutableSet.of());
  }

  /**
   * NOTE: this method does not work for getting all annotations of parameters of methods from class
   * files. For that case, use {@link #getAllAnnotationsForParameter(Symbol.MethodSymbol, int)}
   *
   * @param symbol the symbol
   * @return all annotations on the symbol and on the type of the symbol
   */
  public static Stream<? extends AnnotationMirror> getAllAnnotations(Symbol symbol) {
    // for methods, we care about annotations on the return type, not on the method type itself
    Stream<? extends AnnotationMirror> typeUseAnnotations = getTypeUseAnnotations(symbol);
    return Stream.concat(symbol.getAnnotationMirrors().stream(), typeUseAnnotations);
  }

  /**
   * Check if any direct annotation a symbol matches a given predicate.
   *
   * @param symbol the symbol
   * @param predicate the predicate to match annotation names against
   * @return true if any annotation on the symbol matches the predicate, false otherwise
   */
  public static boolean hasAnyAnnotationMatching(Symbol symbol, Predicate<String> predicate) {
    // check for declaration annotations
    for (AnnotationMirror annotationMirror : symbol.getAnnotationMirrors()) {
      if (predicate.test(annotationMirror.getAnnotationType().toString())) {
        return true;
      }
    }
    // Check for type-use annotations. For methods, look on the return type and its enclosing types,
    // since NullAway treats an annotation before the outer class of a nested return type as
    // applying to the full return type.
    if (symbol instanceof Symbol.MethodSymbol methodSymbol) {
      for (Type currentType = methodSymbol.getReturnType();
          currentType != null && !currentType.hasTag(TypeTag.NONE);
          currentType = currentType.getEnclosingType()) {
        for (AnnotationMirror annotationMirror : currentType.getAnnotationMirrors()) {
          if (predicate.test(annotationMirror.getAnnotationType().toString())) {
            return true;
          }
        }
      }
    } else {
      for (AnnotationMirror annotationMirror : symbol.type.getAnnotationMirrors()) {
        if (predicate.test(annotationMirror.getAnnotationType().toString())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Retrieve the {@code value} attribute of a method annotation of some type.
   *
   * @param methodSymbol A method to check for the annotation.
   * @param annotName The qualified name of the annotation.
   * @return The {@code value} attribute of the annotation, or {@code null} if the annotation is not
   *     present.
   */
  public static @Nullable String getAnnotationValue(
      Symbol.MethodSymbol methodSymbol, String annotName) {
    AnnotationMirror annot = findAnnotation((Symbol) methodSymbol, annotName, true);
    return annot == null ? null : getAnnotationValue(annot);
  }

  /**
   * Retrieve the {@code value} attribute of a method annotation of some type where the {@code
   * value} is an array.
   *
   * @param methodSymbol A method to check for the annotation.
   * @param annotName The qualified name or simple name of the annotation depending on the value of
   *     {@code exactMatch}.
   * @param exactMatch If true, the annotation name must match the full qualified name given in
   *     {@code annotName}, otherwise, simple names will be checked.
   * @return The {@code value} attribute of the annotation as a {@code Set}, or {@code null} if the
   *     annotation is not present.
   */
  public static @Nullable Set<String> getAnnotationValueArray(
      Symbol.MethodSymbol methodSymbol, String annotName, boolean exactMatch) {
    AnnotationMirror annot = findAnnotation(methodSymbol, annotName, exactMatch);
    if (annot == null) {
      return null;
    }
    Map<? extends ExecutableElement, ? extends AnnotationValue> elementValues =
        annot.getElementValues();
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elementValues.entrySet()) {
      ExecutableElement elem = entry.getKey();
      if (elem.getSimpleName().contentEquals("value")) {
        @SuppressWarnings("unchecked")
        List<AnnotationValue> values = (List<AnnotationValue>) entry.getValue().getValue();
        return values.stream().map((av) -> ((String) av.getValue())).collect(Collectors.toSet());
      }
    }
    return null;
  }

  /**
   * Retrieve the specific annotation of a symbol.
   *
   * @param symbol A symbol to check for the annotation.
   * @param annotName The qualified name or simple name of the annotation depending on the value of
   *     {@code exactMatch}.
   * @param exactMatch If true, the annotation name must match the full qualified name given in
   *     {@code annotName}, otherwise, simple names will be checked.
   * @return an {@code AnnotationMirror} representing that annotation, or null in case the
   *     annotation with a given name {@code annotName} doesn't exist in {@code symbol}.
   */
  public static @Nullable AnnotationMirror findAnnotation(
      Symbol symbol, String annotName, boolean exactMatch) {
    AnnotationMirror annot = null;
    for (AnnotationMirror annotationMirror : symbol.getAnnotationMirrors()) {
      String name = AnnotationUtils.annotationName(annotationMirror);
      if ((exactMatch && name.equals(annotName)) || (!exactMatch && name.endsWith(annotName))) {
        annot = annotationMirror;
        break;
      }
    }
    return annot;
  }

  /**
   * Retrieve the {@code value} attribute from an annotation mirror.
   *
   * @param annot the annotation mirror
   * @return the {@code value} attribute, or {@code null} if the annotation has no string-valued
   *     {@code value} element
   */
  public static @Nullable String getAnnotationValue(AnnotationMirror annot) {
    Map<? extends ExecutableElement, ? extends AnnotationValue> elementValues =
        annot.getElementValues();
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elementValues.entrySet()) {
      ExecutableElement elem = entry.getKey();
      if (elem.getSimpleName().contentEquals("value")) {
        Object value = entry.getValue().getValue();
        if (value instanceof String string) {
          return string;
        }
      }
    }
    return null;
  }

  /**
   * Works for method parameters defined either in source or in class files
   *
   * @param symbol the method symbol
   * @param paramInd index of the parameter
   * @return all declaration and type-use annotations for the parameter
   */
  public static Stream<? extends AnnotationMirror> getAllAnnotationsForParameter(
      Symbol.MethodSymbol symbol, int paramInd) {
    Symbol.VarSymbol varSymbol = symbol.getParameters().get(paramInd);
    Type parameterType = symbol.type.getParameterTypes().get(paramInd);
    Stream<Attribute.TypeCompound> typeUseAnnotations =
        Stream.concat(
                getTypeUseAnnotationsIncludingEnclosingTypes(varSymbol.type),
                getTypeUseAnnotationsIncludingEnclosingTypes(parameterType))
            .distinct();
    return Stream.concat(varSymbol.getAnnotationMirrors().stream(), typeUseAnnotations);
  }

  /**
   * Gets the type use annotations on a symbol, ignoring annotations on components of the type (type
   * arguments, wildcards, etc.)
   */
  public static Stream<Attribute.TypeCompound> getTypeUseAnnotations(Symbol symbol) {
    Type annotatedType =
        symbol instanceof Symbol.MethodSymbol methodSymbol
            ? methodSymbol.getReturnType()
            : symbol.type;
    if (symbol instanceof Symbol.MethodSymbol) {
      return getTypeUseAnnotationsIncludingEnclosingTypes(annotatedType);
    }
    return annotatedType.getAnnotationMirrors().stream();
  }

  /**
   * Gets the type-use annotations directly on {@code type} or any of its enclosing types.
   *
   * <p>javac models an annotation written before the outer class in a nested type on that enclosing
   * type. For method return and parameter nullability, NullAway treats such an annotation as
   * applying to the full nested type.
   */
  private static Stream<Attribute.TypeCompound> getTypeUseAnnotationsIncludingEnclosingTypes(
      Type type) {
    Stream<Attribute.TypeCompound> annotations = type.getAnnotationMirrors().stream();
    Type enclosingType = type.getEnclosingType();
    return enclosingType == null || enclosingType.hasTag(TypeTag.NONE)
        ? annotations
        : Stream.concat(annotations, getTypeUseAnnotationsIncludingEnclosingTypes(enclosingType));
  }

  /**
   * Check if a field might be null, based on the type.
   *
   * @param symbol symbol for field
   * @param config NullAway config
   * @return true if based on the type, package, and name of the field, the analysis should assume
   *     the field might be null; false otherwise
   */
  public static boolean mayBeNullFieldFromType(
      Symbol symbol, Config config, Handler handler, CodeAnnotationInfo codeAnnotationInfo) {
    return !(symbol.getSimpleName().toString().equals("class")
            || symbol.isEnum()
            || codeAnnotationInfo.isSymbolUnannotated(symbol, config, handler))
        && Nullness.hasNullableOrMonotonicNonNullAnnotation(symbol, config);
  }

  /**
   * Converts a {@link Nullness} to a {@code bool} value.
   *
   * @param nullness The nullness value.
   * @return true if the nullness value represents a {@code Nullable} value. To be more specific, it
   *     returns true if the nullness value is either {@link Nullness#NULL} or {@link
   *     Nullness#NULLABLE}.
   */
  public static boolean nullnessToBool(Nullness nullness) {
    return switch (nullness) {
      case BOTTOM, NONNULL -> false;
      case NULL, NULLABLE -> true;
    };
  }

  /**
   * Checks if {@code symbol} is a method on {@code java.util.Map} (or a subtype) with name {@code
   * methodName} and {@code numParams} parameters
   */
  public static boolean isMapMethod(
      Symbol.MethodSymbol symbol, VisitorState state, String methodName, int numParams) {
    if (!symbol.getSimpleName().toString().equals(methodName)) {
      return false;
    }
    if (symbol.getParameters().size() != numParams) {
      return false;
    }
    Symbol owner = symbol.owner;
    return ASTHelpers.isSubtype(owner.type, MAP_TYPE_SUPPLIER.get(state), state);
  }

  /**
   * Downcasts a {@code @Nullable} argument to {@code NonNull}, returning the argument
   *
   * @throws NullPointerException if argument is {@code null}
   */
  public static <T> T castToNonNull(@Nullable T obj) {
    if (obj == null) {
      throw new NullPointerException("castToNonNull failed!");
    }
    return obj;
  }

  /**
   * Checks if the given array symbol has a {@code @Nullable} annotation for its elements.
   *
   * @param arraySymbol The symbol of the array to check.
   * @param config NullAway configuration.
   * @return true if the array symbol has a {@code @Nullable} annotation for its elements, false
   *     otherwise
   */
  public static boolean isArrayElementNullable(Symbol arraySymbol, Config config) {
    return checkArrayElementAnnotations(
        arraySymbol,
        config,
        Nullness::isNullableAnnotation,
        Nullness::hasNullableDeclarationAnnotation);
  }

  /**
   * Checks if the given varargs symbol has a {@code @Nullable} annotation for its elements. Works
   * for both source and bytecode.
   *
   * @param varargsSymbol the symbol of the varargs parameter
   * @param config NullAway configuration
   * @return true if the varargs symbol has a {@code @Nullable} annotation for its elements, false
   *     otherwise
   */
  public static boolean nullableVarargsElementsForSourceOrBytecode(
      Symbol varargsSymbol, Config config) {
    return isArrayElementNullable(varargsSymbol, config)
        || Nullness.hasNullableDeclarationAnnotation(varargsSymbol, config);
  }

  /**
   * Checks if the given array symbol has a {@code @NonNull} annotation for its elements.
   *
   * @param arraySymbol The symbol of the array to check.
   * @param config NullAway configuration.
   * @return true if the array symbol has a {@code @NonNull} annotation for its elements, false
   *     otherwise
   */
  public static boolean isArrayElementNonNull(Symbol arraySymbol, Config config) {
    return checkArrayElementAnnotations(
        arraySymbol,
        config,
        Nullness::isNonNullAnnotation,
        Nullness::hasNonNullDeclarationAnnotation);
  }

  /**
   * Checks if the given varargs symbol has a {@code @NonNull} annotation for its elements. Works
   * for both source and bytecode.
   *
   * @param varargsSymbol the symbol of the varargs parameter
   * @param config NullAway configuration
   * @return true if the varargs symbol has a {@code @NonNull} annotation for its elements, false
   *     otherwise
   */
  public static boolean nonnullVarargsElementsForSourceOrBytecode(
      Symbol varargsSymbol, Config config) {
    return isArrayElementNonNull(varargsSymbol, config)
        || Nullness.hasNonNullDeclarationAnnotation(varargsSymbol, config);
  }

  /**
   * Checks if the annotations on the elements of some array symbol satisfy some predicate.
   *
   * @param arraySymbol the array symbol
   * @param config NullAway configuration
   * @param typeUseCheck the predicate to check the type-use annotations
   * @param declarationCheck the predicate to check the declaration annotations (applied only to
   *     varargs symbols)
   * @return true if the annotations on the elements of the array symbol satisfy the given
   *     predicates, false otherwise
   */
  private static boolean checkArrayElementAnnotations(
      Symbol arraySymbol,
      Config config,
      BiPredicate<String, Config> typeUseCheck,
      BiPredicate<Symbol, Config> declarationCheck) {
    Type annotatedType =
        arraySymbol instanceof Symbol.MethodSymbol methodSymbol
            ? methodSymbol.getReturnType()
            : arraySymbol.type;
    if (annotatedType instanceof Type.ArrayType arrayType) {
      for (AnnotationMirror annotationMirror :
          arrayType.getComponentType().getAnnotationMirrors()) {
        if (typeUseCheck.test(annotationMirror.getAnnotationType().toString(), config)) {
          return true;
        }
      }
    }
    // For varargs symbols we also check for declaration annotations on the parameter
    // NOTE this flag check does not work for the varargs parameter of a method defined in bytecodes
    if ((arraySymbol.flags() & Flags.VARARGS) != 0) {
      return declarationCheck.test(arraySymbol, config);
    }
    return false;
  }

  /**
   * Does the given symbol have a JetBrains @NotNull declaration annotation? Useful for workarounds
   * in light of https://github.com/uber/NullAway/issues/720
   */
  public static boolean hasJetBrainsNotNullDeclarationAnnotation(Symbol varSymbol) {
    // We explicitly ignore type-use annotations here, looking for @NotNull used as a
    // declaration annotation, which is why this logic is simpler than e.g.
    // NullabilityUtil.getAllAnnotationsForParameter.
    return varSymbol.getAnnotationMirrors().stream()
        .map(a -> a.getAnnotationType().toString())
        .anyMatch(annotName -> annotName.equals(JETBRAINS_NOT_NULL));
  }

  /**
   * Checks if the method invocation is a varargs call, i.e., if individual arguments are being
   * passed in the varargs position. If false, it means that an array is being passed in the varargs
   * position.
   *
   * @param tree the method invocation tree (MethodInvocationTree or NewClassTree)
   * @return true if the method invocation is a varargs call, false otherwise
   */
  public static boolean isVarArgsCall(Tree tree) {
    // javac sets the varargsElement field to a non-null value if the invocation is a varargs call
    Type varargsElement =
        tree instanceof JCTree.JCMethodInvocation jcMethodInvocation
            ? jcMethodInvocation.varargsElement
            : ((JCTree.JCNewClass) tree).varargsElement;
    return varargsElement != null;
  }

  /**
   * strip out enclosing parentheses, type casts and Nullchk operators.
   *
   * @param expr a potentially parenthesised expression.
   * @return the same expression without parentheses.
   */
  public static ExpressionTree stripParensAndCasts(ExpressionTree expr) {
    boolean someChange = true;
    while (someChange) {
      someChange = false;
      if (expr instanceof ParenthesizedTree) {
        expr = ((ParenthesizedTree) expr).getExpression();
        someChange = true;
      }
      if (expr instanceof TypeCastTree) {
        expr = ((TypeCastTree) expr).getExpression();
        someChange = true;
      }

      // Strips Nullchk operator
      if (expr.getKind().equals(OTHER) && expr instanceof JCTree.JCUnary) {
        expr = ((JCTree.JCUnary) expr).getExpression();
        someChange = true;
      }
    }
    return expr;
  }

  /**
   * Returns an updated version of {@code path} with {@code leaf} as the leaf, if needed. If {@code
   * leaf} is already the leaf of {@code path} (compared using reference equality), just return
   * {@code path} unmodified.
   */
  @SuppressWarnings("ReferenceEquality") // deliberate
  public static TreePath pathWithLeaf(TreePath path, Tree leaf) {
    return path.getLeaf() == leaf ? path : new TreePath(path, leaf);
  }

  /**
   * A pair of an expression tree and a VisitorState, used by {@link #stripParensAndUpdateTreePath}
   */
  public record ExprTreeAndState(ExpressionTree expr, VisitorState state) {}

  /**
   * strip out enclosing parentheses, and update the tree path in the VisitorState to point to the
   * stripped expression if the original expression was the leaf of the path
   *
   * @param expr a potentially parenthesised expression.
   * @param state the VisitorState
   * @return the same expression without parentheses, and the updated VisitorState
   */
  public static ExprTreeAndState stripParensAndUpdateTreePath(
      ExpressionTree expr, VisitorState state) {
    TreePath path = state.getPath();
    @SuppressWarnings("ReferenceEquality")
    boolean leafNotExpr = path.getLeaf() != expr;
    if (leafNotExpr) {
      throw new RuntimeException(
          String.format("Wrong leaf %s in path to %s", path.getLeaf(), expr));
    }
    ExpressionTree resultExpr = expr;
    while (resultExpr instanceof ParenthesizedTree) {
      resultExpr = ((ParenthesizedTree) resultExpr).getExpression();
      path = new TreePath(path, resultExpr);
    }
    @SuppressWarnings("ReferenceEquality")
    VisitorState resultState = path == state.getPath() ? state : state.withPath(path);
    return new ExprTreeAndState(resultExpr, resultState);
  }
}
