package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Checks nominal shape recovery independently of the scalar fallback used by the checker. */
public class InferenceTypeShapesTests {

  @Test
  public void recoverOnlyUnambiguousAttributedShapes() {
    StringBuilder source =
        new StringBuilder("import java.util.List; class Host<F> { class Inner {}\n");
    for (String name :
        List.of(
            "parameterOnly",
            "returnOnly",
            "thrownOnly",
            "arrayOnly",
            "enclosingOnly",
            "wildcardOnly",
            "fixedAttribution",
            "unboundWildcardAttribution",
            "fixedWildcardAttribution",
            "quantifiedDeclaration",
            "quantifiedAttribution",
            "fixedDeclaration",
            "conflictingEvidence",
            "mismatchedArity",
            "missingMethodShape",
            "missingClassShape",
            "wildcardKindConflict",
            "wildcardConcreteConflict",
            "errorAttribution",
            "rejectSelf",
            "rejectArray",
            "rejectClass",
            "rejectWildcard",
            "rejectEnclosing")) {
      source
          .append("<T, U> void ")
          .append(name)
          .append(
              "(List<String> strings, List<T> unresolved, List<? extends T> wildcard, T[] array,"
                  + " Host<T>.Inner inner, F fixed) {}\n");
    }
    source.append("}\n");
    CompilationTestHelper.newInstance(ShapeChecker.class, getClass())
        .addSourceLines("Host.java", source.toString())
        .doTest();
  }

  /** Exercises one named matching rule with real symbols and synthetic executable views. */
  @BugPattern(summary = "Checks attributed inference shapes", severity = SUGGESTION)
  public static final class ShapeChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    /**
     * Constructs matching and mismatching executable views without mutating compiler-owned types.
     */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (method == null || method.getTypeParameters().isEmpty()) {
        return NO_MATCH;
      }
      Element t = method.getTypeParameters().get(0);
      Type variable = ((Symbol) t).type;
      var parameters = method.getParameters();
      Type string = state.getSymtab().stringType;
      Type object = state.getSymtab().objectType;
      Type wildcardList = parameters.get(2).type;
      Type.WildcardType wildcard = (Type.WildcardType) wildcardList.getTypeArguments().head;
      Type extendsString =
          TypeMetadataBuilder.TYPE_METADATA_BUILDER.createWildcardType(wildcard, string);
      Type declaration = variable;
      Type attributed = string;
      Type expected = string;
      boolean rejected = false;
      switch (tree.getName().toString()) {
        case "parameterOnly" -> {
          declaration = executable(state, List.of(variable), object, List.of());
          attributed = executable(state, List.of(string), object, List.of());
        }
        case "returnOnly" -> {
          declaration = executable(state, List.of(), variable, List.of());
          attributed = executable(state, List.of(), string, List.of());
        }
        case "thrownOnly" -> {
          declaration = executable(state, List.of(), object, List.of(variable));
          attributed = executable(state, List.of(), object, List.of(string));
        }
        case "arrayOnly" -> {
          declaration = parameters.get(3).type;
          attributed =
              TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(
                  (Type.ArrayType) declaration, string);
        }
        case "enclosingOnly" -> {
          declaration = parameters.get(4).type;
          Type enclosing = withArgument(declaration.getEnclosingType(), string);
          attributed =
              TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
                  declaration, enclosing, declaration.getTypeArguments());
        }
        case "wildcardOnly" -> {
          declaration = wildcardList;
          attributed = withArgument(wildcardList, extendsString);
        }
        case "fixedAttribution" -> {
          attributed = parameters.get(5).type;
          expected = attributed;
        }
        case "fixedWildcardAttribution" -> {
          attributed = withArgument(wildcardList, extendsString);
          expected = attributed;
        }
        case "unboundWildcardAttribution" -> {
          attributed =
              withArgument(
                  wildcardList, new Type.WildcardType(object, BoundKind.UNBOUND, wildcard.tsym));
          expected = attributed;
        }
        case "quantifiedDeclaration" -> {
          declaration =
              new Type.ForAll(
                  com.sun.tools.javac.util.List.of(variable),
                  executable(state, List.of(variable), object, List.of()));
          attributed = executable(state, List.of(string), object, List.of());
        }
        case "quantifiedAttribution" -> {
          declaration = executable(state, List.of(variable), object, List.of());
          attributed =
              new Type.ForAll(
                  com.sun.tools.javac.util.List.nil(),
                  executable(state, List.of(string), object, List.of()));
        }
        case "fixedDeclaration" -> {
          declaration = parameters.get(5).type;
          rejected = true;
        }
        case "conflictingEvidence" -> {
          declaration = executable(state, List.of(variable, variable, variable), object, List.of());
          attributed = executable(state, List.of(string, object, string), object, List.of());
          rejected = true;
        }
        case "mismatchedArity" -> {
          declaration = executable(state, List.of(variable), object, List.of());
          attributed = executable(state, List.of(), object, List.of());
          rejected = true;
        }
        case "missingMethodShape" -> {
          declaration = executable(state, List.of(variable), object, List.of());
          rejected = true;
        }
        case "missingClassShape" -> {
          declaration =
              executable(state, List.of(variable, parameters.get(1).type), object, List.of());
          attributed = executable(state, List.of(string, string), object, List.of());
          rejected = true;
        }
        case "wildcardKindConflict", "wildcardConcreteConflict" -> {
          declaration = executable(state, List.of(variable, wildcard), object, List.of());
          Type second =
              tree.getName().contentEquals("wildcardKindConflict")
                  ? new Type.WildcardType(string, BoundKind.SUPER, wildcard.tsym)
                  : string;
          attributed = executable(state, List.of(string, second), object, List.of());
          rejected = tree.getName().contentEquals("wildcardKindConflict");
        }
        case "errorAttribution" -> {
          attributed = state.getSymtab().errType;
          rejected = true;
        }
        case "rejectSelf" -> {
          attributed = variable;
          rejected = true;
        }
        case "rejectArray" -> {
          attributed = parameters.get(3).type;
          rejected = true;
        }
        case "rejectClass" -> {
          attributed = parameters.get(1).type;
          rejected = true;
        }
        case "rejectWildcard" -> {
          attributed = wildcardList;
          rejected = true;
        }
        case "rejectEnclosing" -> {
          attributed = parameters.get(4).type;
          rejected = true;
        }
        default -> throw new AssertionError(tree.getName());
      }
      Map<Element, Type> shapes =
          InferenceTypeShapes.inferInstantiations(
              declaration,
              attributed,
              method.getTypeParameters(),
              state,
              new NullAway(ErrorProneFlags.fromMap(Map.of("NullAway:OnlyNullMarked", "true")))
                  .getConfig());
      if (rejected) {
        assertThat(shapes).isEmpty();
      } else {
        assertThat(shapes).hasSize(1);
        assertThat(shapes.get(t)).isSameInstanceAs(expected);
      }
      return NO_MATCH;
    }

    /** Builds an executable with precisely the variable occurrences needed by one matching rule. */
    private static Type.MethodType executable(
        VisitorState state, List<Type> parameters, Type result, List<Type> thrown) {
      return new Type.MethodType(
          com.sun.tools.javac.util.List.from(parameters),
          result,
          com.sun.tools.javac.util.List.from(thrown),
          state.getSymtab().methodClass);
    }

    /** Changes one class argument while preserving the enclosing type and nominal class symbol. */
    private static Type withArgument(Type template, Type argument) {
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
          template, template.getEnclosingType(), com.sun.tools.javac.util.List.of(argument));
    }
  }
}
