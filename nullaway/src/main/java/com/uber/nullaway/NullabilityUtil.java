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
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeAnnotationPosition.TypePathEntry;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.JCDiagnostic;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.nullaway.javacutil.AnnotationUtils;

/** Helpful utility methods for nullability analysis. */
public class NullabilityUtil {
  public static final String NULLMARKED_SIMPLE_NAME = "NullMarked";
  public static final String NULLUNMARKED_SIMPLE_NAME = "NullUnmarked";

  private static final Supplier<Type> MAP_TYPE_SUPPLIER = Suppliers.typeFromString("java.util.Map");

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
  @Nullable
  public static Symbol.MethodSymbol getClosestOverriddenMethod(
      Symbol.MethodSymbol method, Types types) {
    // taken from Error Prone MethodOverrides check
    Symbol.ClassSymbol owner = method.enclClass();
    for (Type s : types.closure(owner.type)) {
      if (types.isSameType(s, owner.type)) {
        continue;
      }
      for (Symbol m : s.tsym.members().getSymbolsByName(method.name)) {
        if (!(m instanceof Symbol.MethodSymbol)) {
          continue;
        }
        Symbol.MethodSymbol msym = (Symbol.MethodSymbol) m;
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
  @Nullable
  public static TreePath findEnclosingMethodOrLambdaOrInitializer(
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
  @Nullable
  public static TreePath findEnclosingMethodOrLambdaOrInitializer(TreePath path) {
    return findEnclosingMethodOrLambdaOrInitializer(path, ImmutableSet.of());
  }

  /**
   * NOTE: this method does not work for getting all annotations of parameters of methods from class
   * files. For that case, use {@link #getAllAnnotationsForParameter(Symbol.MethodSymbol, int)}
   *
   * @param symbol the symbol
   * @return all annotations on the symbol and on the type of the symbol
   */
  public static Stream<? extends AnnotationMirror> getAllAnnotations(Symbol symbol, Config config) {
    // for methods, we care about annotations on the return type, not on the method type itself
    Stream<? extends AnnotationMirror> typeUseAnnotations = getTypeUseAnnotations(symbol, config);
    return Stream.concat(symbol.getAnnotationMirrors().stream(), typeUseAnnotations);
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
    AnnotationMirror annot =
        AnnotationUtils.getAnnotationByName(methodSymbol.getAnnotationMirrors(), annotName);
    if (annot == null) {
      return null;
    }

    Map<? extends ExecutableElement, ? extends AnnotationValue> elementValues =
        annot.getElementValues();
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elementValues.entrySet()) {
      ExecutableElement elem = entry.getKey();
      if (elem.getSimpleName().contentEquals("value")) {
        return (String) entry.getValue().getValue();
      }
    }
    // not found
    return null;
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
    AnnotationMirror annot = null;
    for (AnnotationMirror annotationMirror : methodSymbol.getAnnotationMirrors()) {
      String name = AnnotationUtils.annotationName(annotationMirror);
      if ((exactMatch && name.equals(annotName)) || (!exactMatch && name.endsWith(annotName))) {
        annot = annotationMirror;
        break;
      }
    }
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
   * Works for method parameters defined either in source or in class files
   *
   * @param symbol the method symbol
   * @param paramInd index of the parameter
   * @return all declaration and type-use annotations for the parameter
   */
  public static Stream<? extends AnnotationMirror> getAllAnnotationsForParameter(
      Symbol.MethodSymbol symbol, int paramInd) {
    Symbol.VarSymbol varSymbol = symbol.getParameters().get(paramInd);
    return Stream.concat(
        varSymbol.getAnnotationMirrors().stream(),
        symbol.getRawTypeAttributes().stream()
            .filter(
                t ->
                    t.position.type.equals(TargetType.METHOD_FORMAL_PARAMETER)
                        && t.position.parameter_index == paramInd));
  }

  /**
   * Gets the type use annotations on a symbol, ignoring annotations on components of the type (type
   * arguments, wildcards, etc.)
   */
  private static Stream<? extends AnnotationMirror> getTypeUseAnnotations(
      Symbol symbol, Config config) {
    Stream<Attribute.TypeCompound> rawTypeAttributes = symbol.getRawTypeAttributes().stream();
    if (symbol instanceof Symbol.MethodSymbol) {
      // for methods, we want annotations on the return type
      return rawTypeAttributes.filter(
          (t) ->
              t.position.type.equals(TargetType.METHOD_RETURN)
                  && isDirectTypeUseAnnotation(t, config));
    } else {
      // filter for annotations directly on the type
      return rawTypeAttributes.filter(t -> NullabilityUtil.isDirectTypeUseAnnotation(t, config));
    }
  }

  /**
   * Check whether a type-use annotation should be treated as applying directly to the top-level
   * type
   *
   * <p>For example {@code @Nullable List<T> lst} is a direct type use annotation of {@code lst},
   * but {@code List<@Nullable T> lst} is not.
   *
   * @param t the annotation and its position in the type
   * @param config NullAway configuration
   * @return {@code true} if the annotation should be treated as applying directly to the top-level
   *     type, false otherwise
   */
  private static boolean isDirectTypeUseAnnotation(Attribute.TypeCompound t, Config config) {
    // location is a list of TypePathEntry objects, indicating whether the annotation is
    // on an array, inner type, wildcard, or type argument. If it's empty, then the
    // annotation is directly on the type.
    // We care about both annotations directly on the outer type and also those directly
    // on an inner type or array dimension, but wish to discard annotations on wildcards,
    // or type arguments.
    // For arrays, outside JSpecify mode, we treat annotations on the outer type and on any
    // dimension of the array as applying to the nullability of the array itself, not the elements.
    // In JSpecify mode, annotations on array dimensions are *not* treated as applying to the
    // top-level type, consistent with the JSpecify spec.
    // We don't allow mixing of inner types and array dimensions in the same location
    // (i.e. `Foo.@Nullable Bar []` is meaningless).
    // These aren't correct semantics for type use annotations, but a series of hacky
    // compromises to keep some semblance of backwards compatibility until we can do a
    // proper deprecation of the incorrect behaviors for type use annotations when their
    // semantics don't match those of a declaration annotation in the same position.
    // See https://github.com/uber/NullAway/issues/708
    boolean locationHasInnerTypes = false;
    boolean locationHasArray = false;
    for (TypePathEntry entry : t.position.location) {
      switch (entry.tag) {
        case INNER_TYPE:
          locationHasInnerTypes = true;
          break;
        case ARRAY:
          if (config.isJSpecifyMode()) {
            // In JSpecify mode, annotations on array element types do not apply to the top-level
            // type
            return false;
          }
          locationHasArray = true;
          break;
        default:
          // Wildcard or type argument!
          return false;
      }
    }
    // Make sure it's not a mix of inner types and arrays for this annotation's location
    return !(locationHasInnerTypes && locationHasArray);
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
      Symbol symbol, Config config, CodeAnnotationInfo codeAnnotationInfo) {
    return !(symbol.getSimpleName().toString().equals("class")
            || symbol.isEnum()
            || codeAnnotationInfo.isSymbolUnannotated(symbol, config))
        && Nullness.hasNullableAnnotation(symbol, config);
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
    switch (nullness) {
      case BOTTOM:
      case NONNULL:
        return false;
      case NULL:
      case NULLABLE:
        return true;
      default:
        throw new AssertionError("Impossible: " + nullness);
    }
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
}
