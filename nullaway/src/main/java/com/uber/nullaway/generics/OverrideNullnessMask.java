package com.uber.nullaway.generics;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.Names;
import com.uber.nullaway.LibraryModels.PolyNullLocation;

/** A comparison-only mask for trusted PolyNull locations in an overridden signature. */
final class OverrideNullnessMask {

  private static final String ANNOTATION_NAME = "nullaway.synthetic.OverrideNullnessMask";

  private static final Supplier<Type> MASK_ANNOTATION =
      VisitorState.memoize(
          state -> {
            Names names = Names.instance(state.context);
            Symbol.PackageSymbol owner =
                new Symbol.PackageSymbol(
                    names.fromString("nullaway.synthetic"),
                    Symtab.instance(state.context).noSymbol);
            return new Type.ErrorType(names.fromString("OverrideNullnessMask"), owner, Type.noType);
          });

  private OverrideNullnessMask() {}

  /**
   * Marks modeled locations on a detached method type for override comparisons. The marker skips
   * only the nullness comparison at its location; nested structure is still checked. Keeping the
   * mask in type metadata lets it follow wildcard bounds and supertype substitutions.
   */
  static Type.MethodType apply(
      Type.MethodType methodType, ImmutableSet<PolyNullLocation> locations, VisitorState state) {
    if (locations.isEmpty()) {
      return methodType;
    }
    return PolyNullInference.applyToMethodType(methodType, locations, MASK_ANNOTATION.get(state));
  }

  /** Returns whether an annotation is the comparison-only mask, for annotation preservation. */
  static boolean isMaskAnnotation(Type annotationType) {
    return annotationType.tsym.getQualifiedName().contentEquals(ANNOTATION_NAME);
  }

  /** Returns whether either type's top-level nullness is exempt from override comparison. */
  static boolean ignoresNullness(Type lhsType, Type rhsType) {
    return isMasked(lhsType) || isMasked(rhsType);
  }

  /** Returns whether the type carries an override comparison mask at its top level. */
  static boolean isMasked(Type type) {
    return hasMaskAnnotation(type)
        || (type instanceof Type.CapturedType capture
            && hasMaskAnnotation(capture.getUpperBound()));
  }

  /** Checks direct metadata without following potentially recursive captured bounds. */
  private static boolean hasMaskAnnotation(Type type) {
    return type.getAnnotationMirrors().stream()
        .anyMatch(annotation -> isMaskAnnotation(annotation.type));
  }
}
