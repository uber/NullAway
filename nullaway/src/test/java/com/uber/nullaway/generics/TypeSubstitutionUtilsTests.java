package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.DummyOptionsConfig;
import com.uber.nullaway.Nullness;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests operations that copy mutable javac type variables and captured types.
 *
 * <p>These behaviors require types created by an active javac compilation, so each test compiles a
 * small source file with {@link TypeCopyIsolationChecker}. Special field names in that source
 * select the operation that the checker exercises; the assertions themselves run inside the checker
 * while the corresponding javac types are available.
 */
@RunWith(JUnit4.class)
public class TypeSubstitutionUtilsTests {

  @Test
  public void replaceUnboundedWildcardUpperBoundDoesNotMutateFormalTypeVariable() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> typeVarField;
            }
            """)
        .doTest();
  }

  @Test
  public void replaceCapturedTypeWildcardReturnsDetachedCapture() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> capturedTypeField;
            }
            """)
        .doTest();
  }

  @Test
  public void cloneTypeWithMetadataReturnsDetachedMutableTypes() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> typeVarMetadataField;
              Test<?> capturedTypeMetadataField;
            }
            """)
        .doTest();
  }

  @Test
  public void typeWithAnnotAnnotatesTheComponentsOfAnIntersection() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T extends Number & Comparable<T>> {
              Test<?> intersectionMetadataField;
            }
            """)
        .doTest();
  }

  @Test
  public void intersectionIsNullableOnlyWhenEveryComponentIs() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T extends Number & Comparable<T>> {
              Test<?> intersectionNullnessField;
            }
            """)
        .doTest();
  }

  /**
   * Checker that exercises the mutable javac types used by the replacement helpers.
   *
   * <p>The fields named {@code typeVarField} and {@code capturedTypeField} exercise the two public
   * replacement helpers. The fields named {@code typeVarMetadataField} and {@code
   * capturedTypeMetadataField} exercise the metadata-copying path used by {@link
   * TypeSubstitutionUtils#typeWithAnnot}. The field named {@code intersectionMetadataField}
   * exercises the same method on an intersection-typed upper bound, and the field named {@code
   * intersectionNullnessField} exercises {@link Nullness#isNullableAnnotated} on such a bound.
   * Other variable declarations are ignored.
   */
  @BugPattern(summary = "Checks that copied javac types are detached", severity = SUGGESTION)
  public static final class TypeCopyIsolationChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {

    private static final String TYPE_VAR_FIELD = "typeVarField";
    private static final String CAPTURED_TYPE_FIELD = "capturedTypeField";
    private static final String TYPE_VAR_METADATA_FIELD = "typeVarMetadataField";
    private static final String CAPTURED_TYPE_METADATA_FIELD = "capturedTypeMetadataField";
    private static final String INTERSECTION_METADATA_FIELD = "intersectionMetadataField";
    private static final String INTERSECTION_NULLNESS_FIELD = "intersectionNullnessField";

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      String fieldName = tree.getName().toString();
      TestTypeContext testTypeContext = createTestTypeContext(tree, state);
      switch (fieldName) {
        case TYPE_VAR_FIELD -> checkUnboundedWildcardReplacement(testTypeContext);
        case CAPTURED_TYPE_FIELD -> checkCapturedWildcardReplacement(testTypeContext, tree, state);
        case TYPE_VAR_METADATA_FIELD -> checkTypeVariableMetadataCopy(testTypeContext);
        case CAPTURED_TYPE_METADATA_FIELD ->
            checkCapturedTypeMetadataCopy(testTypeContext, tree, state);
        case INTERSECTION_METADATA_FIELD -> checkIntersectionMetadataCopy(testTypeContext);
        case INTERSECTION_NULLNESS_FIELD -> checkIntersectionNullness(testTypeContext);
        default -> {
          throw new RuntimeException("Unknown field name: " + fieldName);
        }
      }
      return NO_MATCH;
    }

    /**
     * Extracts the compiler types shared by all scenarios from a synthetic {@code Test<?>} field.
     */
    private static TestTypeContext createTestTypeContext(VariableTree tree, VisitorState state) {
      Type.ClassType fieldType = (Type.ClassType) ASTHelpers.getType(tree);
      Type.WildcardType sourceWildcard = (Type.WildcardType) fieldType.getTypeArguments().head;
      Type.TypeVar formalTypeVariable = (Type.TypeVar) fieldType.tsym.type.getTypeArguments().head;
      Type originalUpperBound = formalTypeVariable.getUpperBound();
      Type nullableAnnotationType = GenericsChecks.getSyntheticNullableAnnotType(state);
      Type updatedUpperBound =
          TypeSubstitutionUtils.typeWithAnnot(originalUpperBound, nullableAnnotationType);
      Type.WildcardType unboundedWildcard =
          new Type.WildcardType(
              sourceWildcard.type, BoundKind.UNBOUND, sourceWildcard.tsym, formalTypeVariable);
      return new TestTypeContext(
          sourceWildcard,
          formalTypeVariable,
          originalUpperBound,
          updatedUpperBound,
          nullableAnnotationType,
          unboundedWildcard);
    }

    /**
     * Checks that replacing an implicit unbounded-wildcard bound copies its formal type variable.
     */
    private static void checkUnboundedWildcardReplacement(TestTypeContext context) {
      Type.WildcardType updatedWildcard =
          TypeSubstitutionUtils.replaceUnboundedWildcardUpperBound(
              context.unboundedWildcard(), context.updatedUpperBound());
      assertThat(context.formalTypeVariable().getUpperBound())
          .isSameInstanceAs(context.originalUpperBound());
      assertThat(updatedWildcard).isNotSameInstanceAs(context.unboundedWildcard());
      assertThat(updatedWildcard.bound).isNotSameInstanceAs(context.formalTypeVariable());
      assertThat(updatedWildcard.bound.tsym).isSameInstanceAs(context.formalTypeVariable().tsym);
      assertThat(updatedWildcard.bound.lower).isSameInstanceAs(context.formalTypeVariable().lower);
      assertThat(updatedWildcard.bound.getUpperBound())
          .isSameInstanceAs(context.updatedUpperBound());
    }

    /** Checks that adding metadata to a type variable does not share its mutable upper bound. */
    private static void checkTypeVariableMetadataCopy(TestTypeContext context) {
      Type.TypeVar updatedTypeVariable =
          (Type.TypeVar)
              TypeSubstitutionUtils.typeWithAnnot(
                  context.formalTypeVariable(), context.nullableAnnotationType());
      updatedTypeVariable.setUpperBound(context.updatedUpperBound());
      assertThat(context.formalTypeVariable().getUpperBound())
          .isSameInstanceAs(context.originalUpperBound());
      assertThat(updatedTypeVariable.getUpperBound()).isSameInstanceAs(context.updatedUpperBound());
      assertThat(updatedTypeVariable.baseType())
          .isSameInstanceAs(context.formalTypeVariable().baseType());
      assertThat(context.formalTypeVariable().getAnnotationMirrors()).isEmpty();
      assertThat(updatedTypeVariable.getAnnotationMirrors()).isNotEmpty();
    }

    /**
     * Checks that annotating an intersection annotates each of its components and leaves the
     * intersection itself unannotated, as JSpecify requires. NullAway used to attach the annotation
     * to the intersection itself, which threw {@code AssertionError} on JDK 17 and produced a plain
     * class type on JDK 21.
     */
    private static void checkIntersectionMetadataCopy(TestTypeContext context) {
      Type originalBound = context.originalUpperBound();
      Type updatedBound = context.updatedUpperBound();
      assertThat(updatedBound).isInstanceOf(Type.IntersectionClassType.class);
      assertThat(updatedBound.getAnnotationMirrors()).isEmpty();
      assertThat(componentNames(updatedBound)).containsExactly("Number", "Comparable").inOrder();
      assertThat(componentAnnotations(updatedBound))
          .containsExactly(
              List.of(context.nullableAnnotationType()), List.of(context.nullableAnnotationType()))
          .inOrder();
      assertThat(componentAnnotations(originalBound)).containsExactly(List.of(), List.of());
    }

    /**
     * Checks that an intersection is nullable when every component is annotated {@code @Nullable},
     * and is not when only some of them are.
     */
    private static void checkIntersectionNullness(TestTypeContext context) {
      Type.IntersectionClassType bound = (Type.IntersectionClassType) context.originalUpperBound();
      Type nullableNumber =
          TypeSubstitutionUtils.typeWithAnnot(
              bound.getComponents().head, context.nullableAnnotationType());
      Type.IntersectionClassType partlyNullableBound =
          new Type.IntersectionClassType(
              com.sun.tools.javac.util.List.of(nullableNumber, bound.getComponents().tail.head),
              (Symbol.ClassSymbol) bound.tsym,
              bound.allInterfaces);
      // A name ending in ".Nullable" is recognized without consulting the configuration.
      DummyOptionsConfig config = new DummyOptionsConfig();
      assertWithMessage("isNullableAnnotated(@Nullable Number & @Nullable Comparable<T>)")
          .that(Nullness.isNullableAnnotated(context.updatedUpperBound(), config))
          .isTrue();
      assertWithMessage("isNullableAnnotated(@Nullable Number & Comparable<T>)")
          .that(Nullness.isNullableAnnotated(partlyNullableBound, config))
          .isFalse();
    }

    /** Returns the simple names of the components of an intersection type, in order. */
    private static List<String> componentNames(Type intersectionType) {
      return ((Type.IntersectionClassType) intersectionType)
          .getComponents().stream().map(c -> c.tsym.getSimpleName().toString()).toList();
    }

    /** Returns the annotation types on each component of an intersection type, in order. */
    private static List<List<Type>> componentAnnotations(Type intersectionType) {
      return ((Type.IntersectionClassType) intersectionType)
          .getComponents().stream()
              .map(c -> c.getAnnotationMirrors().stream().map(a -> (Type) a.type).toList())
              .toList();
    }

    /** Checks that replacing a capture's backing wildcard returns a fully detached capture. */
    private static void checkCapturedWildcardReplacement(
        TestTypeContext context, VariableTree tree, VisitorState state) {
      Type.CapturedType capturedType = createCapturedType(context, tree, state);
      Type.WildcardType replacementWildcard =
          new Type.WildcardType(
              context.updatedUpperBound(), BoundKind.EXTENDS, context.sourceWildcard().tsym);
      Type.CapturedType updatedCapture =
          TypeSubstitutionUtils.replaceCapturedTypeWildcard(capturedType, replacementWildcard);
      assertThat(updatedCapture).isNotSameInstanceAs(capturedType);
      assertThat(updatedCapture.tsym).isSameInstanceAs(capturedType.tsym);
      assertThat(updatedCapture.lower).isSameInstanceAs(capturedType.lower);
      assertThat(capturedType.wildcard).isSameInstanceAs(context.unboundedWildcard());
      assertThat(updatedCapture.wildcard).isSameInstanceAs(replacementWildcard);

      // A javac metadata clone delegates this setter to the original capture. Mutating the
      // replacement therefore proves that it is a genuinely detached object, not such a clone.
      updatedCapture.setUpperBound(context.updatedUpperBound());
      assertThat(capturedType.getUpperBound()).isSameInstanceAs(context.originalUpperBound());
      assertThat(updatedCapture.getUpperBound()).isSameInstanceAs(context.updatedUpperBound());
    }

    /** Checks that adding metadata to a capture does not share its mutable upper bound. */
    private static void checkCapturedTypeMetadataCopy(
        TestTypeContext context, VariableTree tree, VisitorState state) {
      Type.CapturedType capturedType = createCapturedType(context, tree, state);
      Type.CapturedType updatedCapture =
          (Type.CapturedType)
              TypeSubstitutionUtils.typeWithAnnot(capturedType, context.nullableAnnotationType());
      updatedCapture.setUpperBound(context.updatedUpperBound());
      assertThat(capturedType.getUpperBound()).isSameInstanceAs(context.originalUpperBound());
      assertThat(updatedCapture.getUpperBound()).isSameInstanceAs(context.updatedUpperBound());
      assertThat(updatedCapture.baseType()).isSameInstanceAs(capturedType.baseType());
      assertThat(capturedType.getAnnotationMirrors()).isEmpty();
      assertThat(updatedCapture.getAnnotationMirrors()).isNotEmpty();
    }

    /** Creates the synthetic captured type used by both capture-copy scenarios. */
    private static Type.CapturedType createCapturedType(
        TestTypeContext context, VariableTree tree, VisitorState state) {
      Symbol owner = ASTHelpers.getSymbol(tree);
      return new Type.CapturedType(
          context.formalTypeVariable().tsym.name,
          owner,
          context.originalUpperBound(),
          state.getSymtab().botType,
          context.unboundedWildcard());
    }

    /** Compiler types derived from one synthetic field and shared by a single test scenario. */
    private record TestTypeContext(
        Type.WildcardType sourceWildcard,
        Type.TypeVar formalTypeVariable,
        Type originalUpperBound,
        Type updatedUpperBound,
        Type nullableAnnotationType,
        Type.WildcardType unboundedWildcard) {}
  }
}
