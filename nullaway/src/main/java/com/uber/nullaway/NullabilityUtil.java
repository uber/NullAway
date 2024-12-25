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
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeAnnotationPosition;
import com.sun.tools.javac.code.TypeAnnotationPosition.TypePathEntry;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.JCDiagnostic;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementKind;
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
   * files. For that case, use {@link #getAllAnnotationsForParameter(Symbol.MethodSymbol, int,
   * Config)}
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
   * Retrieve the specific annotation of a method.
   *
   * @param methodSymbol A method to check for the annotation.
   * @param annotName The qualified name or simple name of the annotation depending on the value of
   *     {@code exactMatch}.
   * @param exactMatch If true, the annotation name must match the full qualified name given in
   *     {@code annotName}, otherwise, simple names will be checked.
   * @return an {@code AnnotationMirror} representing that annotation, or null in case the
   *     annotation with a given name {@code annotName} doesn't exist in {@code methodSymbol}.
   */
  public static @Nullable AnnotationMirror findAnnotation(
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
    return annot;
  }

  /**
   * Works for method parameters defined either in source or in class files
   *
   * @param symbol the method symbol
   * @param paramInd index of the parameter
   * @param config NullAway configuration
   * @return all declaration and type-use annotations for the parameter
   */
  public static Stream<? extends AnnotationMirror> getAllAnnotationsForParameter(
      Symbol.MethodSymbol symbol, int paramInd, Config config) {
    Symbol.VarSymbol varSymbol = symbol.getParameters().get(paramInd);
    return Stream.concat(
        varSymbol.getAnnotationMirrors().stream(),
        symbol.getRawTypeAttributes().stream()
            .filter(
                t ->
                    t.position.type.equals(TargetType.METHOD_FORMAL_PARAMETER)
                        && t.position.parameter_index == paramInd
                        && NullabilityUtil.isDirectTypeUseAnnotation(t, symbol, config)));
  }

  /**
   * Gets the type use annotations on a symbol, ignoring annotations on components of the type (type
   * arguments, wildcards, etc.)
   */
  public static Stream<Attribute.TypeCompound> getTypeUseAnnotations(Symbol symbol, Config config) {
    return getTypeUseAnnotations(symbol, config, /* onlyDirect= */ true);
  }

  /**
   * Gets the type use annotations on a symbol
   *
   * @param symbol the symbol
   * @param config NullAway configuration
   * @param onlyDirect if true, only return annotations that are directly on the type, not on
   *     components of the type (type arguments, wildcards, array contents, etc.)
   * @return the type use annotations on the symbol
   */
  private static Stream<Attribute.TypeCompound> getTypeUseAnnotations(
      Symbol symbol, Config config, boolean onlyDirect) {
    // Adapted from Error Prone's MoreAnnotations class:
    // https://github.com/google/error-prone/blob/5f71110374e63f3c35b661f538295fa15b5c1db2/check_api/src/main/java/com/google/errorprone/util/MoreAnnotations.java#L84-L91
    Symbol typeAnnotationOwner =
        symbol.getKind().equals(ElementKind.PARAMETER) ? symbol.owner : symbol;
    Stream<Attribute.TypeCompound> rawTypeAttributes =
        typeAnnotationOwner.getRawTypeAttributes().stream();
    if (symbol instanceof Symbol.MethodSymbol) {
      // for methods, we want annotations on the return type
      return rawTypeAttributes.filter(
          (t) ->
              t.position.type.equals(TargetType.METHOD_RETURN)
                  && (!onlyDirect || isDirectTypeUseAnnotation(t, symbol, config)));
    } else {
      // filter for annotations directly on the type
      return rawTypeAttributes.filter(
          t ->
              targetTypeMatches(symbol, t.position)
                  && (!onlyDirect || isDirectTypeUseAnnotation(t, symbol, config)));
    }
  }

  // Adapted from Error Prone MoreAnnotations:
  // https://github.com/google/error-prone/blob/5f71110374e63f3c35b661f538295fa15b5c1db2/check_api/src/main/java/com/google/errorprone/util/MoreAnnotations.java#L128
  private static boolean targetTypeMatches(Symbol sym, TypeAnnotationPosition position) {
    switch (sym.getKind()) {
      case LOCAL_VARIABLE:
        return position.type == TargetType.LOCAL_VARIABLE;
      case FIELD:
      case ENUM_CONSTANT: // treated like a field
        return position.type == TargetType.FIELD;
      case CONSTRUCTOR:
      case METHOD:
        return position.type == TargetType.METHOD_RETURN;
      case PARAMETER:
        if (position.type.equals(TargetType.METHOD_FORMAL_PARAMETER)) {
          int parameterIndex = position.parameter_index;
          if (position.onLambda != null) {
            com.sun.tools.javac.util.List<JCTree.JCVariableDecl> lambdaParams =
                position.onLambda.params;
            return parameterIndex < lambdaParams.size()
                && lambdaParams.get(parameterIndex).sym.equals(sym);
          } else {
            return ((Symbol.MethodSymbol) sym.owner).getParameters().indexOf(sym) == parameterIndex;
          }
        } else {
          return false;
        }
      case CLASS:
      case ENUM: // treated like a class
        // There are no type annotations on the top-level type of the class/enum being declared,
        // only on other types in the signature (e.g. `class Foo extends Bar<@A Baz> {}`).
        return false;
      default:
        // Compare with toString() to preserve compatibility with JDK 11
        if (sym.getKind().toString().equals("RECORD")) {
          // Records are treated like classes
          return false;
        } else {
          throw new AssertionError("unsupported element kind " + sym.getKind() + " symbol " + sym);
        }
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
   * @param symbol the symbol for the annotated element
   * @param config NullAway configuration
   * @return {@code true} if the annotation should be treated as applying directly to the top-level
   *     type, false otherwise
   */
  private static boolean isDirectTypeUseAnnotation(
      Attribute.TypeCompound t, Symbol symbol, Config config) {
    // location is a list of TypePathEntry objects, indicating whether the annotation is
    // on an array, inner type, wildcard, or type argument. If it's empty, then the
    // annotation is directly on the type.
    // We care about both annotations directly on the outer type and also those directly
    // on an inner type or array dimension, but wish to discard annotations on wildcards,
    // or type arguments.
    // For arrays, when the LegacyAnnotationLocations flag is passed, we treat annotations on the
    // outer type and on any dimension of the array as applying to the nullability of the array
    // itself, not the elements.
    // In JSpecify mode and without the LegacyAnnotationLocations flag, annotations on array
    // dimensions are *not* treated as applying to the top-level type, consistent with the JSpecify
    // spec.
    // Annotations which are *not* on the inner type are not treated as being applied to the inner
    // type. This can be bypassed the LegacyAnnotationLocations flag, in which
    // annotations on all locations are treated as applying to the inner type.
    // We don't allow mixing of inner types and array dimensions in the same location
    // (i.e. `Foo.@Nullable Bar []` is meaningless).
    // These aren't correct semantics for type use annotations, but a series of hacky
    // compromises to keep some semblance of backwards compatibility until we can do a
    // proper deprecation of the incorrect behaviors for type use annotations when their
    // semantics don't match those of a declaration annotation in the same position.
    // See https://github.com/uber/NullAway/issues/708
    boolean locationHasInnerTypes = false;
    boolean locationHasArray = false;
    int innerTypeCount = 0;
    int nestingDepth = getNestingDepth(symbol.type);
    for (TypePathEntry entry : t.position.location) {
      switch (entry.tag) {
        case INNER_TYPE:
          locationHasInnerTypes = true;
          innerTypeCount++;
          break;
        case ARRAY:
          if (config.isJSpecifyMode() || !config.isLegacyAnnotationLocation()) {
            // Annotations on array element types do not apply to the top-level
            // type outside of legacy mode
            return false;
          }
          locationHasArray = true;
          break;
        default:
          // Wildcard or type argument!
          return false;
      }
    }
    if (config.isLegacyAnnotationLocation()) {
      // Make sure it's not a mix of inner types and arrays for this annotation's location
      return !(locationHasInnerTypes && locationHasArray);
    }
    // For non-nested classes annotations apply to the innermost type.
    if (!isTypeOfNestedClass(symbol.type)) {
      return true;
    }
    // For nested classes the annotation is only valid if it is on the innermost type.
    return innerTypeCount == nestingDepth - 1;
  }

  private static int getNestingDepth(Type type) {
    int depth = 0;
    for (Type curr = type;
        curr != null && !curr.hasTag(TypeTag.NONE);
        curr = curr.getEnclosingType()) {
      depth++;
    }
    return depth;
  }

  private static boolean isTypeOfNestedClass(Type type) {
    return type.tsym != null && type.tsym.owner instanceof Symbol.ClassSymbol;
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
            || codeAnnotationInfo.isSymbolUnannotated(symbol, config, null))
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
    if (getTypeUseAnnotations(arraySymbol, config, /* onlyDirect= */ false)
        .anyMatch(
            t -> {
              for (TypeAnnotationPosition.TypePathEntry entry : t.position.location) {
                if (entry.tag == TypeAnnotationPosition.TypePathEntryKind.ARRAY) {
                  if (typeUseCheck.test(t.type.toString(), config)) {
                    return true;
                  }
                }
              }
              return false;
            })) {
      return true;
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
  public static boolean hasJetBrainsNotNullDeclarationAnnotation(Symbol.VarSymbol varSymbol) {
    // We explicitly ignore type-use annotations here, looking for @NotNull used as a
    // declaration annotation, which is why this logic is simpler than e.g.
    // NullabilityUtil.getAllAnnotationsForParameter.
    return varSymbol.getAnnotationMirrors().stream()
        .map(a -> a.getAnnotationType().toString())
        .anyMatch(annotName -> annotName.equals(JETBRAINS_NOT_NULL));
  }
}
