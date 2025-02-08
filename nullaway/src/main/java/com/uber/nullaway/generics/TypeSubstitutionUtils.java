package com.uber.nullaway.generics;

import static com.uber.nullaway.generics.PreservedAnnotationTreeVisitor.TYPE_METADATA_BUILDER;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Nullness;
import java.util.Collections;

/** Utility method related to substituting type arguments for type variables. */
public class TypeSubstitutionUtils {

  /**
   * Returns the type of {@code sym} as a member of {@code t}.
   *
   * @param types the {@link Types} instance
   * @param t the enclosing type
   * @param sym the symbol
   * @return the type of {@code sym} as a member of {@code t}
   */
  public static Type memberType(Types types, Type t, Symbol sym) {
    Type origType = sym.type;
    Type memberType = types.memberType(t, sym);
    return restoreExplicitNullabilityAnnotations(origType, memberType);
  }

  private static Type restoreExplicitNullabilityAnnotations(Type origType, Type newType) {
    // we want to visit the types together; they should have the same structure, except that some
    // stuff from origType got substituted out.  if at any point we encounter an explicit @Nullable
    // or @NonNull annotation on origType, restore it to the corresponding substituted type in
    // newType.  NOTE: we cannot just tweak the substitution, since explicit annotations may appear
    // only at some occurrences of a type.  TEST THIS.
    return new RestoreNullnessAnnotationsVisitor().visit(newType, origType);
  }

  // NOTE: com.sun.tools.javac.code.Type.StructuralTypeMapping is really good to look at; need to
  // mimick that structure
  @SuppressWarnings("ReferenceEquality")
  private static class RestoreNullnessAnnotationsVisitor extends Types.MapVisitor<Type> {

    @Override
    public Type visitMethodType(Type.MethodType t, Type other) {
      Type.MethodType otherMethodType = (Type.MethodType) other;
      List<Type> argtypes = t.argtypes;
      Type restype = t.restype;
      List<Type> thrown = t.thrown;
      List<Type> argtypes1 = visitTypeLists(argtypes, otherMethodType.argtypes);
      Type restype1 = visit(restype, otherMethodType.restype);
      List<Type> thrown1 = visitTypeLists(thrown, otherMethodType.thrown);
      if (argtypes1 == argtypes && restype1 == restype && thrown1 == thrown) {
        return t;
      } else {
        return new Type.MethodType(argtypes1, restype1, thrown1, t.tsym);
      }
    }

    @Override
    public Type visitClassType(Type.ClassType t, Type other) {
      Type outer = t.getEnclosingType();
      Type outer1 = visit(outer, other.getEnclosingType());
      List<Type> typarams = t.getTypeArguments();
      List<Type> typarams1 = visitTypeLists(typarams, other.getTypeArguments());
      if (outer1 == outer && typarams1 == typarams) {
        return t;
      } else {
        return new Type.ClassType(outer1, typarams1, t.tsym, t.getMetadata());
      }
    }

    @Override
    public Type visitTypeVar(Type.TypeVar t, Type type) {
      for (Attribute.TypeCompound annot : type.getAnnotationMirrors()) {
        if (annot.type.tsym == null) {
          continue;
        }
        String qualifiedName = annot.type.tsym.getQualifiedName().toString();
        if (Nullness.isNullableAnnotation(qualifiedName, null)) {
          com.sun.tools.javac.util.List<Attribute.TypeCompound> nullableAnnotationCompound =
              com.sun.tools.javac.util.List.from(
                  Collections.singletonList(
                      new Attribute.TypeCompound(
                          annot.type, com.sun.tools.javac.util.List.nil(), null)));
          TypeMetadata typeMetadata = TYPE_METADATA_BUILDER.create(nullableAnnotationCompound);
          Type underlyingType = t;
          Type newType = TYPE_METADATA_BUILDER.cloneTypeWithMetadata(underlyingType, typeMetadata);
          return newType;
        }
      }
      return t;
    }

    // just returns argtypes itself if there is no change
    private List<Type> visitTypeLists(List<Type> argtypes, List<Type> argtypes1) {
      ListBuffer<Type> buf = new ListBuffer<>();
      boolean changed = false;
      for (List<Type> l = argtypes, l1 = argtypes1; l.nonEmpty(); l = l.tail, l1 = l1.tail) {
        Type t = l.head;
        Type t1 = l1.head;
        Type t2 = visit(t, t1);
        buf.append(t2);
        if (t2 != t) {
          changed = true;
        }
      }
      return changed ? buf.toList() : argtypes;
    }
  }

  /**
   * Substitutes the types in {@code to} for the types in {@code from} in {@code t}.
   *
   * @param types the {@link Types} instance
   * @param t the type to which to perform the substitution
   * @param from the types that will be substituted out
   * @param to the types that will be substituted in
   * @return the type resulting from the substitution
   */
  public static Type subst(Types types, Type t, List<Type> from, List<Type> to) {
    return types.subst(t, from, to);
  }
}
