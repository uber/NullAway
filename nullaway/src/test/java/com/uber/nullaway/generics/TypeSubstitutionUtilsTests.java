package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

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
import com.uber.nullaway.Config;
import com.uber.nullaway.DummyOptionsConfig;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests operations that copy mutable javac type variables, captured types, and wildcards.
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
  public void restoringBothBoundsOfSuperWildcardKeepsBoth() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> superWildcardRestorationField;
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
   * TypeSubstitutionUtils#typeWithAnnot}. The field named {@code superWildcardRestorationField}
   * exercises {@link TypeSubstitutionUtils#restoreExplicitNullabilityAnnotations} on a {@code
   * super} wildcard. Any other field name fails the compilation.
   */
  @BugPattern(summary = "Checks that copied javac types are detached", severity = SUGGESTION)
  public static final class TypeCopyIsolationChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {

    private static final String TYPE_VAR_FIELD = "typeVarField";
    private static final String CAPTURED_TYPE_FIELD = "capturedTypeField";
    private static final String TYPE_VAR_METADATA_FIELD = "typeVarMetadataField";
    private static final String CAPTURED_TYPE_METADATA_FIELD = "capturedTypeMetadataField";
    private static final String SUPER_WILDCARD_RESTORATION_FIELD = "superWildcardRestorationField";

    /**
     * Config that recognizes only the built-in nullness annotation names.
     *
     * <p>{@link DummyOptionsConfig} throws from every method; annotation matching calls only the
     * four overridden here.
     */
    private static final Config ANNOTATION_NAMES_ONLY_CONFIG =
        new DummyOptionsConfig() {
          @Override
          public boolean isJSpecifyMode() {
            return true;
          }

          @Override
          public boolean acknowledgeAndroidRecent() {
            return false;
          }

          @Override
          public boolean isCustomNullableAnnotation(String annotationName) {
            return false;
          }

          @Override
          public boolean isCustomNonnullAnnotation(String annotationName) {
            return false;
          }
        };

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
        case SUPER_WILDCARD_RESTORATION_FIELD ->
            checkSuperWildcardRestoration(testTypeContext, state);
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

    /**
     * Checks that restoring annotations onto a {@code super} wildcard keeps both of its bounds.
     *
     * <p>The annotated wildcard is {@code ? super @Nullable String} whose formal type variable has
     * the upper bound {@code @Nullable Object}. Restoring its annotations onto an unannotated
     * {@code ? super String} changes the lower bound and the implicit upper bound, so the result
     * must carry {@code @Nullable} on both. {@link
     * GenericsUtils#wildcardUpperBound(Type.WildcardType, VisitorState, Config,
     * com.uber.nullaway.handlers.Handler)} reads the implicit upper bound from the wildcard's
     * {@code bound} field and returns {@code Object} when that field is {@code null}.
     */
    private static void checkSuperWildcardRestoration(TestTypeContext context, VisitorState state) {
      Type stringType = state.getSymtab().stringType;
      Type.TypeVar annotatedFormalTypeVariable =
          TYPE_METADATA_BUILDER.createDetachedTypeVar(
              context.formalTypeVariable(), context.updatedUpperBound());
      Type.WildcardType annotatedWildcard =
          new Type.WildcardType(
              TypeSubstitutionUtils.typeWithAnnot(stringType, context.nullableAnnotationType()),
              BoundKind.SUPER,
              context.sourceWildcard().tsym,
              annotatedFormalTypeVariable);
      Type.WildcardType unannotatedWildcard =
          new Type.WildcardType(
              stringType,
              BoundKind.SUPER,
              context.sourceWildcard().tsym,
              context.formalTypeVariable());

      Type.WildcardType restored =
          (Type.WildcardType)
              TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                  annotatedWildcard, unannotatedWildcard, ANNOTATION_NAMES_ONLY_CONFIG);

      assertThat(restored.kind).isEqualTo(BoundKind.SUPER);
      assertThat(annotationNames(restored.type)).containsExactly("nullaway.synthetic.Nullable");
      assertThat(restored.bound).isNotNull();
      assertThat(annotationNames(restored.bound.getUpperBound()))
          .containsExactly("nullaway.synthetic.Nullable");
    }

    /** Returns the names of the type annotations on {@code type}, as NullAway matches them. */
    private static List<String> annotationNames(Type type) {
      return type.getAnnotationMirrors().stream()
          .map(annotation -> annotation.getAnnotationType().toString())
          .toList();
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
